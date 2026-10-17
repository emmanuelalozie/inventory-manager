package com.example.inventix.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for receiving stock on a purchase order: {"lines": [{"lineId": 7, "quantity": 20}], "note": "..."}.
 * Lines that aren't listed are left as they are.
 */
public record ReceiptRequest(
        @NotEmpty(message = "lines must list at least one line")
        List<@NotNull(message = "lines must not contain null") @Valid Line> lines,

        @Size(max = 500, message = "Note must be at most 500 characters")
        String note) {

    public record Line(
            @NotNull(message = "lineId is required")
            Long lineId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than 0")
            Integer quantity) {
    }
}
