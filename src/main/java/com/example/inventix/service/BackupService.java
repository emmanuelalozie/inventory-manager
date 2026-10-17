package com.example.inventix.service;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.dto.BackupStatusResponse;

public interface BackupService {

    /**
     * Writes a consistent zip copy of the H2 database into the configured backup folder
     * (inventix.backup.dir). The file name is generated here, never taken from the caller.
     */
    BackupResponse createBackup();

    /** The data and backup folders, the backups that exist (newest first) and any pending restore. */
    BackupStatusResponse getStatus();

    /**
     * Asks for the named backup to be restored on the next start of the backend. Nothing changes until then; the
     * current database is kept next to the restored one.
     *
     * @throws com.example.inventix.exception.BadRequestException if the name isn't a backup file in the backup folder
     * @throws com.example.inventix.exception.BackupNotAvailableException if the database isn't a managed file
     */
    BackupStatusResponse requestRestore(String fileName);

    /** Withdraws a pending restore, if any. */
    BackupStatusResponse cancelRestore();
}
