package com.example.inventix.dto;

import java.time.Instant;
import java.util.List;

/**
 * Result of GET /api/backup: where the data lives, the backups that exist (newest first) and any restore waiting
 * for the next start.
 *
 * @param databaseFile   the H2 database file, or null when the database isn't a managed file (e.g. in tests)
 * @param pendingRestore file name of the backup that will be restored on the next start, or null
 */
public record BackupStatusResponse(String databaseFile, String backupDir, String pendingRestore, List<Backup> backups) {

    public record Backup(String fileName, long sizeBytes, Instant createdAt) {
    }
}
