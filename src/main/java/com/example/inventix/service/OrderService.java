package com.example.inventix.service;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderStatus;

import java.util.List;

/**
 * Service interface for managing orders, providing CRUD operations and
 * additional functionality for updating order status.
 */
public interface OrderService {

    Order createOrder(Order order);

    Order getOrderById(Long id);

    Order updateOrder(Long id, Order order);

    void deleteOrder(Long id);

    List<Order> getAllOrders();

    Order updateOrderStatus(Long id, OrderStatus status);
}
