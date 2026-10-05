package com.example.inventix.service;

import com.example.inventix.dto.BackupResponse;
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
        BackupService service = new BackupServiceImpl(fileDb, backupDir.toString());

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
        BackupService service = new BackupServiceImpl(fileDb, workDir.resolve("backups").toString());

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
        BackupService service = new BackupServiceImpl(memoryDb, workDir.resolve("backups").toString());

        assertThatThrownBy(service::createBackup)
                .isInstanceOf(BackupNotAvailableException.class)
                .hasMessageContaining("in memory");
    }
}
