package com.example.inventix.dto;

import com.example.inventix.model.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for creating an order or replacing its items: {"items": [{"productId": 1, "quantity": 2}]}.
 * A missing or empty list creates an empty order.
 */
public record OrderRequest(List<@NotNull(message = "items must not contain null") @Valid OrderItemRequest> items) {

    public List<OrderItem> toItems() {
        return items == null ? List.of() : items.stream().map(OrderItemRequest::toEntity).toList();
    }
}
