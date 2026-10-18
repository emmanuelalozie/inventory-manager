package com.example.inventix.service;

import com.example.inventix.config.DataLocation;
import com.example.inventix.dto.BackupResponse;
import com.example.inventix.dto.BackupStatusResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.BackupNotAvailableException;
import com.example.inventix.service.impl.BackupServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs BACKUP TO against a real H2 file database. Everything is written under build/ so a test run
 * never touches ./data or ./backups.
 */
class BackupServiceTest {

    private Path workDir;
    private JdbcTemplate fileDb;

    @BeforeEach
    void setUp() throws IOException {
        workDir = Path.of("build", "backup-service-test", Long.toString(System.nanoTime())).toAbsolutePath();
        Files.createDirectories(workDir);
        fileDb = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:file:" + workDir.resolve("db").resolve("inventix") + ";DB_CLOSE_DELAY=-1", "sa", ""));
        fileDb.execute("CREATE TABLE sample (id INT PRIMARY KEY, name VARCHAR(20))");
        fileDb.execute("INSERT INTO sample VALUES (1, 'first')");
    }

    @AfterEach
    void tearDown() {
        fileDb.execute("SHUTDOWN");
    }

    @Test
    void createBackup_fileDatabase_writesZipIntoBackupFolder() throws IOException {
        Path backupDir = workDir.resolve("backups");
        BackupService service = new BackupServiceImpl(fileDb, backupDir.toString(), "");

        BackupResponse response = service.createBackup();

        assertThat(response.fileName()).matches("inventix-backup-\\d{8}-\\d{6}\\.zip");
        Path written = Path.of(response.path());
        assertThat(written.getParent()).isEqualTo(backupDir);
        assertThat(written).exists();
        assertThat(response.sizeBytes()).isEqualTo(Files.size(written)).isPositive();
        assertThat(response.createdAt()).isNotNull();
        try (ZipFile zip = new ZipFile(written.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().map(ZipEntry::getName).forEach(names::add);
            assertThat(names).anyMatch(name -> name.endsWith("inventix.mv.db"));
        }
    }

    @Test
    void createBackup_twiceInTheSameSecond_keepsBothFiles() {
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), "");

        BackupResponse first = service.createBackup();
        BackupResponse second = service.createBackup();

        assertThat(second.path()).isNotEqualTo(first.path());
        assertThat(Path.of(first.path())).exists();
        assertThat(Path.of(second.path())).exists();
    }

    @Test
    void createBackup_inMemoryDatabase_throwsBackupNotAvailable() {
        JdbcTemplate memoryDb = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:backup-service-test", "sa", ""));
        BackupService service = new BackupServiceImpl(memoryDb, workDir.resolve("backups").toString(), "");

        assertThatThrownBy(service::createBackup)
                .isInstanceOf(BackupNotAvailableException.class)
                .hasMessageContaining("in memory");
    }

    @Test
    void restore_roundTrip_backupIsPutBackOnTheNextStart_andTheOldDatabaseIsKept() throws IOException {
        Path dataDir = workDir.resolve("db");
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), dataDir.toString());
        BackupResponse backup = service.createBackup();
        fileDb.execute("INSERT INTO sample VALUES (2, 'after the backup')");

        BackupStatusResponse status = service.requestRestore(backup.fileName());
        assertThat(status.pendingRestore()).isEqualTo(backup.fileName());
        assertThat(status.backups()).extracting(BackupStatusResponse.Backup::fileName).containsExactly(backup.fileName());
        assertThat(status.databaseFile()).isEqualTo(dataDir.resolve("inventix.mv.db").toString());

        // The app stops, and on the next start (before the database is opened) the restore is applied.
        fileDb.execute("SHUTDOWN");
        List<String> messages = new ArrayList<>();
        DataLocation.applyPendingRestore(dataDir, messages::add, messages::add);

        assertThat(messages).anyMatch(m -> m.startsWith("Restored the database from"));
        assertThat(dataDir.resolve(DataLocation.RESTORE_MARKER)).doesNotExist();
        try (Stream<Path> files = Files.list(dataDir)) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .anyMatch(name -> name.matches("inventix-before-restore-\\d{8}-\\d{6}\\.mv\\.db"));
        }
        fileDb = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:file:" + dataDir.resolve("inventix") + ";DB_CLOSE_DELAY=-1", "sa", ""));
        assertThat(fileDb.queryForList("SELECT name FROM sample ORDER BY id", String.class)).containsExactly("first");
    }

    @Test
    void requestRestore_acceptsOnlyBackupFileNames_andCanBeCancelled() throws IOException {
        Path dataDir = workDir.resolve("db");
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), dataDir.toString());
        BackupResponse backup = service.createBackup();
        Files.writeString(workDir.resolve("backups").resolve("not-a-backup.zip"), "nope");

        assertThatThrownBy(() -> service.requestRestore("../db/inventix.mv.db")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requestRestore("missing.zip")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requestRestore("not-a-backup.zip")).isInstanceOf(BadRequestException.class);

        service.requestRestore(backup.fileName());
        assertThat(service.cancelRestore().pendingRestore()).isNull();
        assertThat(dataDir.resolve(DataLocation.RESTORE_MARKER)).doesNotExist();
    }

    @Test
    void requestRestore_withoutAManagedDataFolder_isNotAvailable() {
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), "");

        assertThatThrownBy(() -> service.requestRestore("x.zip")).isInstanceOf(BackupNotAvailableException.class);
    }

    @Test
    void createAutomaticBackup_usesItsOwnPrefix_andIsTheLatest() {
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), "");
        assertThat(service.latestAutomaticBackup()).isEmpty();

        BackupResponse auto = service.createAutomaticBackup();

        assertThat(auto.fileName()).matches("inventix-auto-\\d{8}-\\d{6}\\.zip");
        assertThat(service.latestAutomaticBackup()).isPresent();
    }

    @Test
    void pruneAutomaticBackups_keepsTheNewest_andNeverTouchesManualBackups() throws IOException {
        Path backupDir = workDir.resolve("backups");
        Files.createDirectories(backupDir);
        for (String name : List.of("inventix-auto-20261001-090000.zip", "inventix-auto-20261002-090000.zip",
                "inventix-auto-20261003-090000.zip", "inventix-auto-20261003-090000-1.zip",
                "inventix-backup-20260901-090000.zip", "notes.zip")) {
            Files.writeString(backupDir.resolve(name), "x");
        }
        BackupService service = new BackupServiceImpl(fileDb, backupDir.toString(), "");

        int deleted = service.pruneAutomaticBackups(2);

        assertThat(deleted).isEqualTo(2);
        try (Stream<Path> files = Files.list(backupDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(
                    "inventix-auto-20261003-090000.zip", "inventix-auto-20261003-090000-1.zip",
                    "inventix-backup-20260901-090000.zip", "notes.zip");
        }
        assertThat(service.pruneAutomaticBackups(0)).as("at least one is always kept").isEqualTo(1);
        assertThat(backupDir.resolve("inventix-auto-20261003-090000-1.zip")).exists();
    }

    @Test
    void serverMode_everyBackupActionIsNotAvailable() {
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString(), "", false);

        assertThatThrownBy(service::createBackup).isInstanceOf(BackupNotAvailableException.class);
        assertThatThrownBy(service::createAutomaticBackup).isInstanceOf(BackupNotAvailableException.class);
        assertThatThrownBy(service::getStatus).isInstanceOf(BackupNotAvailableException.class);
        assertThatThrownBy(() -> service.requestRestore("x.zip")).isInstanceOf(BackupNotAvailableException.class);
        assertThatThrownBy(service::cancelRestore).isInstanceOf(BackupNotAvailableException.class);
        assertThat(workDir.resolve("backups")).doesNotExist();
    }
}
