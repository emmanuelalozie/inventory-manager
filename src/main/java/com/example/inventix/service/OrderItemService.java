package com.example.inventix.service;

import com.example.inventix.model.OrderItem;

import java.util.List;

public interface OrderItemService {

    OrderItem createOrderItem(Long orderId, OrderItem orderItem);

    OrderItem updateOrderItem(Long itemId, OrderItem updatedItem);

    void deleteOrderItem(Long itemId);

    List<OrderItem> getOrderItemsByOrderId(Long orderId);
}
