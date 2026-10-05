package com.example.inventix.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for changing an order item's quantity: {"quantity": 3}.
 */
public record QuantityUpdateRequest(
        @NotNull(message = "quantity is required") @Positive(message = "quantity must be greater than 0") Integer quantity) {
}
