package com.example.inventix.service;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;

import java.util.List;

public interface OrderService {

    Order createOrder(Order order);

    Order createOrderWithItems(Order order, List<OrderItem> orderItems);

    Order getOrderById(Long id);

    Order updateOrder(Long id, Order updatedOrder);

    Order addOrderItems(Long orderId, List<OrderItem> newItems);

    void deleteOrder(Long id);

    List<Order> getAllOrders();

    Order updateOrderStatus(Long id, OrderStatus status);
}
