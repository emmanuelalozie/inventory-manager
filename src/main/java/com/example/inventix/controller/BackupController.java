package com.example.inventix.controller;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.dto.BackupStatusResponse;
import com.example.inventix.dto.RestoreRequest;
import com.example.inventix.service.BackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;

    @Autowired
    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    // No request body: the server picks the folder and file name.
    @PostMapping
    public ResponseEntity<BackupResponse> createBackup() {
        return ResponseEntity.status(HttpStatus.CREATED).body(backupService.createBackup());
    }

    @GetMapping
    public BackupStatusResponse getStatus() {
        return backupService.getStatus();
    }

    /** Schedules a restore for the next start of the backend; nothing changes until then. */
    @PostMapping("/restore")
    public BackupStatusResponse requestRestore(@Valid @RequestBody RestoreRequest request) {
        return backupService.requestRestore(request.fileName());
    }

    @DeleteMapping("/restore")
    public BackupStatusResponse cancelRestore() {
        return backupService.cancelRestore();
    }
}
