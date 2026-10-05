package com.example.inventix.service.impl;

import com.example.inventix.dto.BackupResponse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Runs H2's BACKUP TO command. It can run while the app is using the database: H2 copies the
 * transaction log too, so the zip is transactionally consistent.
 * Not @Transactional on purpose: BACKUP is a plain statement and needs no JPA transaction.
 */
@Service
public class BackupServiceImpl implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupServiceImpl.class);

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // org.h2.api.ErrorCode.DATABASE_IS_NOT_PERSISTENT; H2 is only a runtime dependency, so the value is copied here.
    static final int H2_DATABASE_IS_NOT_PERSISTENT = 90126;

    private final JdbcTemplate jdbcTemplate;
    private final Path backupDir;

    @Autowired
    public BackupServiceImpl(JdbcTemplate jdbcTemplate, @Value("${inventix.backup.dir:./backups}") String backupDir) {
        this.jdbcTemplate = jdbcTemplate;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
    }

    @Override
    public BackupResponse createBackup() {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new BackupFailedException("Could not create the backup folder " + backupDir, e);
        }

        Path target = nextBackupFile();
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
    private Path nextBackupFile() {
        String base = "inventix-backup-" + LocalDateTime.now().format(FILE_TIMESTAMP);
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
