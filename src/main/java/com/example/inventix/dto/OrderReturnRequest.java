package com.example.inventix.dto;

import com.example.inventix.model.StockMovement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for POST /api/orders/{id}/returns: the products a customer sent back and how many of each, with an
 * optional note (e.g. why) that goes in the stock ledger.
 */
public record OrderReturnRequest(
        @NotEmpty(message = "Return at least one product")
        List<@NotNull(message = "lines must not contain null") @Valid Line> lines,

        @Size(max = OrderReturnRequest.NOTE_MAX_LENGTH, message = "Note must be at most 400 characters")
        String note) {

    /** Leaves room in the movement note for the "Returned from order #..." prefix. */
    public static final int NOTE_MAX_LENGTH = StockMovement.NOTE_MAX_LENGTH - 100;

    public record Line(
            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than 0")
            Integer quantity) {
    }
}
