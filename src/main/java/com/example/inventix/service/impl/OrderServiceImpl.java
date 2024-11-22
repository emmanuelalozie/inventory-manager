package com.example.inventix.service.impl;

import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemService orderItemService;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemService orderItemService) {
        this.orderRepository = orderRepository;
        this.orderItemService = orderItemService;
    }

    @Override
    public Order createOrder(Order order) {
        order.setOrderItems(new ArrayList<>()); // Initialize empty list for OrderItems
        return orderRepository.save(order);
    }

    @Override
    public Order createOrderWithItems(Order order, List<OrderItem> orderItems) {
        Order savedOrder = createOrder(order); // Create the base order
        orderItems.forEach(item -> orderItemService.createOrderItem(savedOrder.getId(), item));
        return getOrderById(savedOrder.getId()); // Return the updated order
    }

    @Override
    public Order addOrderItems(Long orderId, List<OrderItem> newItems) {
        newItems.forEach(item -> orderItemService.createOrderItem(orderId, item));
        return getOrderById(orderId);
    }

    @Override
    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
    }

    @Override
    public Order updateOrder(Long id, Order updatedOrder) {
        Order existingOrder = getOrderById(id); // Retrieve existing order

        // Clear existing items
        existingOrder.getOrderItems().clear();

        // Add new items and save them to the database
        List<OrderItem> newItems = updatedOrder.getOrderItems();
        newItems.forEach(item -> {
            OrderItem createdItem = orderItemService.createOrderItem(existingOrder.getId(), item);
            existingOrder.getOrderItems().add(createdItem); // Add created items to the order
        });

        // Save the updated order and return
        return orderRepository.save(existingOrder);
    }

    @Override
    public void deleteOrder(Long id) {
        Order orderToDelete = getOrderById(id);

        if (orderToDelete.getStatus() != OrderStatus.DELIVERED) {
            orderToDelete.getOrderItems().forEach(item -> orderItemService.deleteOrderItem(item.getId()));
        }

        orderRepository.delete(orderToDelete);
    }

    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Override
    public Order updateOrderStatus(Long id, OrderStatus status) {
        Order orderToUpdate = getOrderById(id);
        orderToUpdate.setStatus(status);
        return orderRepository.save(orderToUpdate);
    }
}
