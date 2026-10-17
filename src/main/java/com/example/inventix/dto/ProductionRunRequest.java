package com.example.inventix.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request body for planning a production run or changing a planned one:
 * {"productId": 4, "quantity": 25, "notes": "..."}. version is optional on PUT and rejects stale edits.
 */
public record ProductionRunRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than 0")
        Integer quantity,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes,

        Long version) {
}
