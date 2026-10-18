package com.example.inventix.config;

import com.example.inventix.dto.BackupResponse;
import com.example.inventix.exception.BackupFailedException;
import com.example.inventix.service.BackupService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomaticBackupsTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private final BackupService backupService = mock(BackupService.class);
    private final AutomaticBackups automatic = new AutomaticBackups(backupService, Duration.ofHours(24), 14, true,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private void backupSucceeds() {
        when(backupService.createAutomaticBackup())
                .thenReturn(new BackupResponse("inventix-auto-x.zip", "/b/inventix-auto-x.zip", 1, NOW));
    }

    @Test
    void backUpIfDue_noAutomaticBackupYet_backsUpAndPrunes() {
        when(backupService.latestAutomaticBackup()).thenReturn(Optional.empty());
        backupSucceeds();

        automatic.backUpIfDue();

        verify(backupService).createAutomaticBackup();
        verify(backupService).pruneAutomaticBackups(14);
    }

    @Test
    void backUpIfDue_recentBackup_doesNothing() {
        when(backupService.latestAutomaticBackup()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(3))));

        automatic.backUpIfDue();

        verify(backupService, never()).createAutomaticBackup();
    }

    @Test
    void backUpIfDue_oldBackup_backsUp() {
        when(backupService.latestAutomaticBackup()).thenReturn(Optional.of(NOW.minus(Duration.ofHours(25))));
        backupSucceeds();

        automatic.backUpIfDue();

        verify(backupService).createAutomaticBackup();
    }

    @Test
    void backUpOnShutdown_backsUp_unlessTurnedOff() {
        backupSucceeds();
        automatic.backUpOnShutdown();
        verify(backupService).createAutomaticBackup();

        BackupService other = mock(BackupService.class);
        new AutomaticBackups(other, Duration.ofHours(24), 14, false, Clock.systemUTC()).backUpOnShutdown();
        verify(other, never()).createAutomaticBackup();
    }

    @Test
    void aFailedBackup_isLogged_notThrown_andNothingIsPruned() {
        when(backupService.latestAutomaticBackup()).thenReturn(Optional.empty());
        when(backupService.createAutomaticBackup()).thenThrow(new BackupFailedException("disk full", null));

        assertThatCode(automatic::backUpIfDue).doesNotThrowAnyException();
        verify(backupService, never()).pruneAutomaticBackups(14);
    }
}
