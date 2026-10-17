package com.example.inventix.service;

import com.example.inventix.dto.BackupResponse;

public interface BackupService {

    /**
     * Writes a consistent zip copy of the H2 database into the configured backup folder
     * (inventix.backup.dir). The file name is generated here, never taken from the caller.
     */
    BackupResponse createBackup();
}
