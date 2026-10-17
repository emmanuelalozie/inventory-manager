package com.example.inventix.exception;

/**
 * Writing the backup file failed (folder not writable, disk full, ...).
 */
public class BackupFailedException extends RuntimeException {
    public BackupFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
