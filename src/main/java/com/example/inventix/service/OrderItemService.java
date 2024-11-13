package com.example.inventix.service;

import com.example.inventix.model.OrderItem;

public interface OrderItemService {
    OrderItem createOrderItem(OrderItem orderItem);

    void deleteOrderItem(Long id);

}
