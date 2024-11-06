package com.example.inventix.service.impl;

import com.example.inventix.model.OrderItem;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.service.OrderItemService;
import org.springframework.beans.factory.annotation.Autowired;

public class OrderItemServiceImpl implements OrderItemService {

    private final OrderItemRepository orderItemRepository;

    @Autowired
    public OrderItemServiceImpl(OrderItemRepository orderItemRepository) {
        this.orderItemRepository = orderItemRepository;
    }

    public OrderItem saveOrderItem(OrderItem orderItem) {
        return orderItemRepository.save(orderItem);
    }

    public void deleteOrderItem(Long id) {
        orderItemRepository.deleteById(id);
    }

}
