package com.example.inventix.dto;

import com.example.inventix.model.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing an order's status: {"status": "SHIPPED"}.
 */
public record OrderStatusRequest(@NotNull(message = "status is required") OrderStatus status) {
}
