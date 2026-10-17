package com.example.inventix.dto;

import com.example.inventix.model.ProductionRunStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for completing or cancelling a production run: {"status": "COMPLETED"}.
 */
public record ProductionRunStatusRequest(@NotNull(message = "status is required") ProductionRunStatus status) {
}
