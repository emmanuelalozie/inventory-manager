package com.example.inventix.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for creating a draft purchase order or replacing a draft's supplier, notes and lines:
 * {"supplierId": 1, "notes": "...", "lines": [{"productId": 3, "quantity": 50, "unitCost": 4.20}]}.
 * version is optional on PUT and rejects stale edits.
 */
public record PurchaseOrderRequest(
        @NotNull(message = "supplierId is required")
        Long supplierId,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes,

        List<@NotNull(message = "lines must not contain null") @Valid Line> lines,

        Long version) {

    public List<Line> linesOrEmpty() {
        return lines == null ? List.of() : lines;
    }

    public record Line(
            @NotNull(message = "productId is required")
            Long productId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than 0")
            Integer quantity,

            @DecimalMin(value = "0", message = "unitCost must be zero or greater")
            BigDecimal unitCost) {
    }
}
