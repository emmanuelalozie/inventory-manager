package com.example.inventix.service.impl;

import com.example.inventix.config.DataLocation;
import com.example.inventix.dto.BackupResponse;
import com.example.inventix.dto.BackupStatusResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.BackupFailedException;
import com.example.inventix.exception.BackupNotAvailableException;
import com.example.inventix.service.BackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs H2's BACKUP TO command. It can run while the app is using the database: H2 copies the
 * transaction log too, so the zip is transactionally consistent.
 * Not @Transactional on purpose: BACKUP is a plain statement and needs no JPA transaction.
 */
@Service
public class BackupServiceImpl implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupServiceImpl.class);

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static final String MANUAL_PREFIX = "inventix-backup-";
    // Automatic backups get their own prefix, so clean-up can never delete a backup someone made by hand.
    static final String AUTOMATIC_PREFIX = "inventix-auto-";
    // inventix-auto-20261018-091500.zip, or -1, -2... for two in the same second.
    private static final Pattern AUTOMATIC_NAME =
            Pattern.compile(Pattern.quote(AUTOMATIC_PREFIX) + "(\\d{8}-\\d{6})(?:-(\\d+))?\\.zip");

    // org.h2.api.ErrorCode.DATABASE_IS_NOT_PERSISTENT; H2 is only a runtime dependency, so the value is copied here.
    static final int H2_DATABASE_IS_NOT_PERSISTENT = 90126;

    private final JdbcTemplate jdbcTemplate;
    private final Path backupDir;
    // Null when the database isn't a managed file (inventix.data.dir unset, e.g. the in-memory test database).
    private final Path dataDir;

    @Autowired
    public BackupServiceImpl(JdbcTemplate jdbcTemplate, @Value("${inventix.backup.dir:./backups}") String backupDir,
                             @Value("${inventix.data.dir:}") String dataDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.dataDir = dataDir.isBlank() ? null : Path.of(dataDir).toAbsolutePath().normalize();
    }

    @Override
    public BackupStatusResponse getStatus() {
        List<BackupStatusResponse.Backup> backups = new ArrayList<>();
        if (Files.isDirectory(backupDir)) {
            try (Stream<Path> files = Files.list(backupDir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".zip") && Files.isRegularFile(f))
                        .forEach(f -> {
                            try {
                                backups.add(new BackupStatusResponse.Backup(f.getFileName().toString(), Files.size(f),
                                        Files.getLastModifiedTime(f).toInstant()));
                            } catch (IOException e) {
                                log.warn("Could not read backup {}: {}", f, e.toString());
                            }
                        });
            } catch (IOException e) {
                throw new BackupFailedException("Could not list the backup folder " + backupDir, e);
            }
        }
        backups.sort(Comparator.comparing(BackupStatusResponse.Backup::createdAt).reversed());
        return new BackupStatusResponse(
                dataDir == null ? null : dataDir.resolve(DataLocation.DATABASE_FILE).toString(),
                backupDir.toString(), pendingRestore(), backups);
    }

    @Override
    public BackupStatusResponse requestRestore(String fileName) {
        if (dataDir == null) {
            throw new BackupNotAvailableException("The database isn't a file managed by Inventix, so it can't be restored here.");
        }
        // A plain file name inside the backup folder: no separators, no "..", must exist and hold a database.
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || !fileName.endsWith(".zip")) {
            throw new BadRequestException("fileName must be the name of a backup file, e.g. inventix-backup-20261017-091500.zip");
        }
        Path backup = backupDir.resolve(fileName).normalize();
        if (!backup.getParent().equals(backupDir) || !Files.isRegularFile(backup)) {
            throw new BadRequestException("There is no backup named " + fileName + " in " + backupDir);
        }
        if (!DataLocation.containsDatabase(backup)) {
            throw new BadRequestException(fileName + " doesn't contain an Inventix database");
        }
        try {
            Files.writeString(dataDir.resolve(DataLocation.RESTORE_MARKER), backup.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BackupFailedException("Could not schedule the restore: " + e.getMessage(), e);
        }
        log.info("Restore of {} scheduled for the next start", backup);
        return getStatus();
    }

    @Override
    public BackupStatusResponse cancelRestore() {
        if (dataDir != null) {
            try {
                Files.deleteIfExists(dataDir.resolve(DataLocation.RESTORE_MARKER));
            } catch (IOException e) {
                throw new BackupFailedException("Could not cancel the restore: " + e.getMessage(), e);
            }
        }
        return getStatus();
    }

    private String pendingRestore() {
        if (dataDir == null) {
            return null;
        }
        Path marker = dataDir.resolve(DataLocation.RESTORE_MARKER);
        try {
            return Files.exists(marker)
                    ? Path.of(Files.readString(marker, StandardCharsets.UTF_8).strip()).getFileName().toString()
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public BackupResponse createBackup() {
        return writeBackup(MANUAL_PREFIX);
    }

    @Override
    public BackupResponse createAutomaticBackup() {
        return writeBackup(AUTOMATIC_PREFIX);
    }

    @Override
    public Optional<Instant> latestAutomaticBackup() {
        return automaticBackupsNewestFirst().stream().findFirst().map(AutomaticBackup::takenAt);
    }

    @Override
    public int pruneAutomaticBackups(int keep) {
        List<AutomaticBackup> backups = automaticBackupsNewestFirst();
        int deleted = 0;
        for (AutomaticBackup old : backups.subList(Math.min(Math.max(keep, 1), backups.size()), backups.size())) {
            try {
                Files.deleteIfExists(old.file());
                deleted++;
                log.info("Deleted old automatic backup {}", old.file());
            } catch (IOException e) {
                log.warn("Could not delete old automatic backup {}: {}", old.file(), e.toString());
            }
        }
        return deleted;
    }

    private record AutomaticBackup(Path file, Instant takenAt, int sameSecondIndex) {
    }

    // Ordered by the time in the file name, not the modification time, which a synced folder may change.
    private List<AutomaticBackup> automaticBackupsNewestFirst() {
        if (!Files.isDirectory(backupDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.filter(Files::isRegularFile)
                    .map(f -> {
                        Matcher m = AUTOMATIC_NAME.matcher(f.getFileName().toString());
                        if (!m.matches()) {
                            return null;
                        }
                        Instant takenAt = LocalDateTime.parse(m.group(1), FILE_TIMESTAMP)
                                .atZone(ZoneId.systemDefault()).toInstant();
                        return new AutomaticBackup(f, takenAt, m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
                    })
                    .filter(b -> b != null)
                    .sorted(Comparator.comparing(AutomaticBackup::takenAt)
                            .thenComparingInt(AutomaticBackup::sameSecondIndex).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list the backup folder {}: {}", backupDir, e.toString());
            return List.of();
        }
    }

    private BackupResponse writeBackup(String prefix) {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new BackupFailedException("Could not create the backup folder " + backupDir, e);
        }

        Path target = nextBackupFile(prefix);
        // The path is built here from the configured folder and a timestamp; quotes are escaped anyway.
        String sql = "BACKUP TO '" + target.toString().replace("'", "''") + "'";
        try {
            jdbcTemplate.execute(sql);
        } catch (DataAccessException e) {
            if (h2ErrorCode(e) == H2_DATABASE_IS_NOT_PERSISTENT) {
                throw new BackupNotAvailableException(
                        "The database is in memory, so there is nothing to back up. Use a file database to enable backups.");
            }
            throw new BackupFailedException("Backup failed: " + e.getMostSpecificCause().getMessage(), e);
        }

        try {
            long size = Files.size(target);
            log.info("Database backed up to {} ({} bytes)", target, size);
            return new BackupResponse(target.getFileName().toString(), target.toString(), size, Instant.now());
        } catch (IOException e) {
            throw new BackupFailedException("Backup was written but could not be read: " + target, e);
        }
    }

    // Two backups in the same second get a numeric suffix instead of overwriting each other.
    private Path nextBackupFile(String prefix) {
        String base = prefix + LocalDateTime.now().format(FILE_TIMESTAMP);
        Path candidate = backupDir.resolve(base + ".zip");
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = backupDir.resolve(base + "-" + i + ".zip");
        }
        return candidate;
    }

    private static int h2ErrorCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        return -1;
    }
}
