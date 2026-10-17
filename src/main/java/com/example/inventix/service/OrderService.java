package com.example.inventix.service;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Optional;

public interface OrderService {

    Order createOrder(Order order);

    Order createOrderWithItems(Order order, List<OrderItem> orderItems);

    /**
     * Returns the order created earlier with this Idempotency-Key, if any.
     */
    Optional<Order> findOrderByIdempotencyKey(String idempotencyKey);

    Order getOrderById(Long id);

    /**
     * Replaces all items of a pending order: stock for the old items is restored, then the new items are added.
     */
    Order updateOrder(Long id, Order updatedOrder);

    Order addOrderItems(Long orderId, List<OrderItem> newItems);

    /**
     * Deletes a pending or cancelled order. Shipped and delivered orders are kept as a record.
     *
     * @throws com.example.inventix.exception.InvalidOrderStateException if the order is shipped or delivered
     */
    void deleteOrder(Long id);

    List<Order> getAllOrders();

    List<Order> getOrdersByStatus(OrderStatus status);

    /**
     * Orders newest first, optionally filtered by status, with their items loaded.
     * Without page and size every match is returned.
     *
     * @throws com.example.inventix.exception.BadRequestException for an invalid page or size
     */
    Page<Order> searchOrders(OrderStatus status, Integer page, Integer size);

    Order updateOrderStatus(Long id, OrderStatus status);
}
