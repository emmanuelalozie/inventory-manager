package com.example.inventix.service.impl;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.OrderNotFoundException;
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
import java.util.ArrayList;
import java.util.List;

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
            Product product = productService.getProductById(item.getProduct().getId());

            if (product.getQuantity() < item.getQuantity()) {
                throw new InsufficientStockException("Not enough stock for product: " + product.getName());
            }

            product.setQuantity(product.getQuantity() - item.getQuantity());
            productService.updateProduct(product.getId(), product);

            item.setOrder(order);
            item.setPricePerUnit(product.getPrice());
            item.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

            totalAmount = totalAmount.add(item.getSubtotal());
        }

        order.setTotalAmount(totalAmount);
        return orderRepository.save(order);
    }

    @Override
    public Order getOrderById(Long id) {
        return (orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id)));
    }

    @Override
    public Order updateOrder(Long id, Order order) {
        Order orderToUpdate = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        BigDecimal totalAmount = BigDecimal.ZERO;

        for (OrderItem item : order.getOrderItems()) {
            Product product = productService.getProductById(item.getProduct().getId());

            // Check if there is enough stock for the new quantity requested
            if (product.getQuantity() < item.getQuantity()) {
                throw new InsufficientStockException("Not enough stock for product: " + product.getName());
            }

            // Adjust product stock based on the requested quantity only once
            product.setQuantity(product.getQuantity() - item.getQuantity());
            productService.updateProduct(product.getId(), product);

            // Set up OrderItem details
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
        Order orderToDelete = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        for (OrderItem item : orderToDelete.getOrderItems()) {
            Product product = item.getProduct();
            if (product != null) { // Add null check here
                product.setQuantity(product.getQuantity() + item.getQuantity());
                productService.updateProduct(product.getId(), product);
            }
        }

        orderRepository.delete(orderToDelete);
    }

    @Override
    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    @Override
    public Order updateOrderStatus(Long id, OrderStatus status) {
        Order orderToUpdate = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        orderToUpdate.setStatus(status);
        return orderToUpdate;
    }

}
