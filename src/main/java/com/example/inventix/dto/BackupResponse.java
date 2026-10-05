package com.example.inventix.dto;

import java.time.Instant;

/**
 * Result of POST /api/backup: the zip file H2 wrote and when.
 *
 * @param fileName  file name only, e.g. inventix-backup-20261004-153000.zip
 * @param path      absolute path of the file on this machine
 * @param sizeBytes size of the zip file
 * @param createdAt when the backup finished
 */
public record BackupResponse(String fileName, String path, long sizeBytes, Instant createdAt) {
}
