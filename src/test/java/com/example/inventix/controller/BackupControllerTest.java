package com.example.inventix.controller;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.dto.BackupStatusResponse;
import com.example.inventix.exception.BackupFailedException;
import com.example.inventix.exception.BackupNotAvailableException;
import com.example.inventix.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for BackupController, with BackupService mocked.
 */
@WebMvcTest(BackupController.class)
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BackupService backupService;

    @Test
    void createBackup_returns201WithFileDetails() throws Exception {
        when(backupService.createBackup()).thenReturn(new BackupResponse(
                "inventix-backup-20261004-153000.zip",
                "C:\\inventix\\backups\\inventix-backup-20261004-153000.zip",
                20480L,
                Instant.parse("2026-10-04T15:30:00Z")));

        mockMvc.perform(post("/api/backup"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("inventix-backup-20261004-153000.zip"))
                .andExpect(jsonPath("$.path").value("C:\\inventix\\backups\\inventix-backup-20261004-153000.zip"))
                .andExpect(jsonPath("$.sizeBytes").value(20480))
                .andExpect(jsonPath("$.createdAt").value("2026-10-04T15:30:00Z"));
    }

    @Test
    void createBackup_inMemoryDatabase_returns409() throws Exception {
        when(backupService.createBackup()).thenThrow(new BackupNotAvailableException("The database is in memory"));

        mockMvc.perform(post("/api/backup"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("The database is in memory"))
                .andExpect(jsonPath("$.path").value("/api/backup"));
    }

    @Test
    void createBackup_writeFails_returns500WithMessage() throws Exception {
        when(backupService.createBackup())
                .thenThrow(new BackupFailedException("Could not create the backup folder X", new IOException("denied")));

        mockMvc.perform(post("/api/backup"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Could not create the backup folder X"));
    }

    @Test
    void getBackup_returnsFoldersBackupsAndPendingRestore() throws Exception {
        when(backupService.getStatus()).thenReturn(new BackupStatusResponse("C:\\data\\inventix.mv.db", "C:\\backups",
                null, List.of(new BackupStatusResponse.Backup("inventix-backup-20261017-091500.zip", 2048,
                        Instant.parse("2026-10-17T09:15:00Z")))));

        mockMvc.perform(get("/api/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseFile").value("C:\\data\\inventix.mv.db"))
                .andExpect(jsonPath("$.backups[0].fileName").value("inventix-backup-20261017-091500.zip"));

        verify(backupService, never()).createBackup();
    }

    @Test
    void restore_isScheduled_validated_andCancellable() throws Exception {
        when(backupService.requestRestore("inventix-backup-20261017-091500.zip")).thenReturn(
                new BackupStatusResponse(null, "C:\\backups", "inventix-backup-20261017-091500.zip", List.of()));
        when(backupService.cancelRestore()).thenReturn(new BackupStatusResponse(null, "C:\\backups", null, List.of()));

        mockMvc.perform(post("/api/backup/restore").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"inventix-backup-20261017-091500.zip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRestore").value("inventix-backup-20261017-091500.zip"));
        mockMvc.perform(post("/api/backup/restore").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/backup/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRestore").doesNotExist());
    }
}
