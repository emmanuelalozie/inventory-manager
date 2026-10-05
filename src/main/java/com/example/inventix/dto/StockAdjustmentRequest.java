package com.example.inventix.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for adjusting a product's stock: {"delta": 25} to restock, {"delta": -3} to remove stock.
 */
public record StockAdjustmentRequest(@NotNull(message = "delta is required") Integer delta) {
}
