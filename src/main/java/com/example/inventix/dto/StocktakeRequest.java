package com.example.inventix.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for POST /api/stocktake: what was counted on the shelf, and a note for the ledger.
 * expectedQuantity is the stock the count sheet showed; when given, a product whose stock changed since then is
 * refused, so a sale made while counting isn't silently undone.
 */
public record StocktakeRequest(
        @NotBlank(message = "A note is required for a stocktake")
        @Size(max = StocktakeRequest.NOTE_MAX_LENGTH, message = "Note must be at most 480 characters")
        String note,

        @NotEmpty(message = "Count at least one product")
        List<@NotNull(message = "lines must not contain null") @Valid Line> lines) {

    /** Leaves room in the movement note (500 characters) for the "Stocktake: " prefix. */
    public static final int NOTE_MAX_LENGTH = 480;

    public record Line(
            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "countedQuantity is required")
            @PositiveOrZero(message = "countedQuantity must be 0 or more")
            Integer countedQuantity,

            Integer expectedQuantity) {
    }
}
