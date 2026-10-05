package com.example.inventix.controller;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.exception.BackupFailedException;
import com.example.inventix.exception.BackupNotAvailableException;
import com.example.inventix.service.BackupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @MockBean
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
    void getBackup_isNotAllowed() throws Exception {
        mockMvc.perform(get("/api/backup"))
                .andExpect(status().isMethodNotAllowed());

        verify(backupService, never()).createBackup();
    }
}
