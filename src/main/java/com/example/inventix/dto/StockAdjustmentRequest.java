package com.example.inventix.dto;

import com.example.inventix.model.StockMovement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for a manual stock adjustment: {"delta": 25, "note": "Found in back room"} to add stock,
 * {"delta": -3, "note": "Damaged"} to remove it. The note is required so the ledger says why.
 * A delta of 0 is rejected by ProductService.
 */
public record StockAdjustmentRequest(
        @NotNull(message = "delta is required")
        Integer delta,

        @NotBlank(message = "A note is required for manual stock adjustments")
        @Size(max = StockMovement.NOTE_MAX_LENGTH, message = "Note must be at most 500 characters")
        String note) {
}
