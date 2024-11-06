package com.example.inventix.service.impl;

import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.OrderService;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;

    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository, ProductService productService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    @Override
    public Order createOrder(Order order) {
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItem item : order.getOrderItems()) {
            Product product = productService.getProductById(item.getProduct().getId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            item.setOrder(order); // Link OrderItem to Order
            item.setPricePerUnit(product.getPrice());
            item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

            totalAmount = totalAmount.add(item.getSubtotal());
        }

        order.setTotalAmount(totalAmount);
        return orderRepository.save(order);
    }

    @Override
    public Optional<Order> getOrderById(Long id){
        return Optional.ofNullable(orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id)));
    }

    @Override
    public Order updateOrder(Long id, Order order) {
        Order orderToUpdate = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        BigDecimal totalAmount = BigDecimal.ZERO;
        orderToUpdate.getOrderItems().clear();

        for (OrderItem item : order.getOrderItems()) {
            Product product = productService.getProductById(item.getProduct().getId())
                    .orElseThrow(() -> new RuntimeException("Product not found"));

            item.setOrder(orderToUpdate);
            item.setPricePerUnit(product.getPrice());
            item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

            totalAmount = totalAmount.add(item.getSubtotal());
            orderToUpdate.getOrderItems().add(item);
        }

        orderToUpdate.setTotalAmount(totalAmount);
        return orderRepository.save(orderToUpdate);
    }

    @Override
    public void deleteOrder(Long id) {
    }

    @Override
    public List<Order> getAllOrders() {
        return List.of();
    }

    @Override
    public Order updateOrderStatus(Long id, OrderStatus status) {
        return null;
    }

}
