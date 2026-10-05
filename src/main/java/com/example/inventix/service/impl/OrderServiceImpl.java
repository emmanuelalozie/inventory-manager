package com.example.inventix.service.impl;

import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.OrderService;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemService orderItemService;
    private final ProductService productService;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository,
                            OrderItemService orderItemService,
                            ProductService productService) {
        this.orderRepository = orderRepository;
        this.orderItemService = orderItemService;
        this.productService = productService;
    }

    @Override
    public Order createOrder(Order order) {
        order.setId(null);
        order.setStatus(OrderStatus.PENDING);
        order.setOrderItems(new ArrayList<>()); // Items are added through OrderItemService so stock is reserved
        order.setTotalAmount(BigDecimal.ZERO);
        return orderRepository.save(order);
    }

    @Override
    public Order createOrderWithItems(Order order, List<OrderItem> orderItems) {
        Order savedOrder = createOrder(order); // Create the base order
        if (orderItems != null) {
            orderItems.forEach(item -> orderItemService.createOrderItem(savedOrder.getId(), item));
        }
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
        Order existingOrder = getOrderById(id);
        if (!holdsReservedStock(existingOrder)) {
            throw new InvalidOrderStateException("Order " + id + " is " + existingOrder.getStatus()
                    + "; only PENDING orders can be changed");
        }

        // Remove the current items (restoring their stock), then add the new ones (reserving stock).
        // Copy the list first because deleting an item removes it from the order's list.
        new ArrayList<>(existingOrder.getOrderItems())
                .forEach(item -> orderItemService.deleteOrderItem(item.getId()));

        List<OrderItem> newItems = updatedOrder.getOrderItems() != null ? updatedOrder.getOrderItems() : List.of();
        newItems.forEach(item -> orderItemService.createOrderItem(existingOrder.getId(), item));

        existingOrder.recalculateTotal();
        return orderRepository.save(existingOrder);
    }

    @Override
    public void deleteOrder(Long id) {
        Order orderToDelete = getOrderById(id);

        // Only pending orders still hold stock. Shipped/delivered stock has left the warehouse
        // and cancelled orders already gave their stock back.
        if (holdsReservedStock(orderToDelete)) {
            new ArrayList<>(orderToDelete.getOrderItems())
                    .forEach(item -> orderItemService.deleteOrderItem(item.getId()));
        }

        orderRepository.delete(orderToDelete);
    }

    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Override
    public List<Order> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status);
    }

    @Override
    public Order updateOrderStatus(Long id, OrderStatus status) {
        Order orderToUpdate = getOrderById(id);
        OrderStatus current = orderToUpdate.getStatus() != null ? orderToUpdate.getStatus() : OrderStatus.PENDING;

        if (current == status) {
            return orderToUpdate;
        }
        if (!current.canTransitionTo(status)) {
            throw new InvalidOrderStateException("Cannot change order " + id + " from " + current + " to " + status);
        }

        if (status == OrderStatus.CANCELLED) {
            // Give the reserved stock back; the items stay on the order as a record.
            orderToUpdate.getOrderItems()
                    .forEach(item -> productService.adjustStock(item.getProduct().getId(), item.getQuantity()));
        }

        orderToUpdate.setStatus(status);
        return orderRepository.save(orderToUpdate);
    }

    // A null status is treated as PENDING (orders created before the status was always set).
    private boolean holdsReservedStock(Order order) {
        return order.getStatus() == null || order.getStatus() == OrderStatus.PENDING;
    }
}
