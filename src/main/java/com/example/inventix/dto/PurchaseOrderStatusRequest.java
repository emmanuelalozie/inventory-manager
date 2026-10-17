package com.example.inventix.dto;

import com.example.inventix.model.PurchaseOrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a purchase order's status: {"status": "ORDERED"}.
 */
public record PurchaseOrderStatusRequest(@NotNull(message = "status is required") PurchaseOrderStatus status) {
}
