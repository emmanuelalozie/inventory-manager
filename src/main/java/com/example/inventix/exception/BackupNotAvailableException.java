package com.example.inventix.exception;

/**
 * The database can't be backed up in its current mode (an in-memory H2 database has no files to copy).
 */
public class BackupNotAvailableException extends RuntimeException {
    public BackupNotAvailableException(String message) {
        super(message);
    }
}
