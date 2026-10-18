package com.example.inventix.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/backup/restore: {"fileName": "inventix-backup-20261017-091500.zip"}, a file in the
 * backup folder (names only, never paths).
 */
public record RestoreRequest(@NotBlank(message = "fileName is required") String fileName) {
}
