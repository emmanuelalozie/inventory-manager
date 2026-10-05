package com.example.inventix.service;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;

import java.util.List;

public interface OrderService {

    Order createOrder(Order order);

    Order createOrderWithItems(Order order, List<OrderItem> orderItems);

    Order getOrderById(Long id);

    /**
     * Replaces all items of a pending order: stock for the old items is restored, then the new items are added.
     */
    Order updateOrder(Long id, Order updatedOrder);

    Order addOrderItems(Long orderId, List<OrderItem> newItems);

    void deleteOrder(Long id);

    List<Order> getAllOrders();

    List<Order> getOrdersByStatus(OrderStatus status);

    Order updateOrderStatus(Long id, OrderStatus status);
}
