package com.example.inventix.dto;

import com.example.inventix.model.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for creating an order or replacing its items: {"customerId": 3, "items": [{"productId": 1, "quantity": 2}]}.
 * A missing or empty list creates an empty order. customerId is optional and only read on create; replacing the
 * items keeps the customer, which PATCH /api/orders/{id}/customer changes.
 */
public record OrderRequest(List<@NotNull(message = "items must not contain null") @Valid OrderItemRequest> items,
                           Long customerId) {

    public List<OrderItem> toItems() {
        return items == null ? List.of() : items.stream().map(OrderItemRequest::toEntity).toList();
    }
}
