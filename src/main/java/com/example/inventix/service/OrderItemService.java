package com.example.inventix.service;

import com.example.inventix.model.OrderItem;

public interface OrderItemService {
    OrderItem saveOrderItem(OrderItem orderItem);

    void deleteOrderItem(Long id);

}
