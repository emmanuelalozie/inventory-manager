package com.example.inventix.dto;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Order as returned by the API, with its items embedded.
 * The order's items must already be loaded (OrderRepository fetches them with the order),
 * because mapping happens after the transaction has ended and open-in-view is off.
 */
public record OrderResponse(
        Long id,
        OrderStatus status,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderItemResponse> orderItems) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getOrderItems() == null ? List.of() : OrderItemResponse.fromAll(order.getOrderItems()));
    }

    public static List<OrderResponse> fromAll(List<Order> orders) {
        return orders.stream().map(OrderResponse::from).toList();
    }
}
