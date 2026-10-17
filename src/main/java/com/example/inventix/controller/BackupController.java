package com.example.inventix.controller;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.service.BackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
}
