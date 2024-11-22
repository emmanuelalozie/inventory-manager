package com.example.inventix.service.impl;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.OrderItemNotFoundException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderItemServiceImpl implements OrderItemService {

    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ProductService productService;

    @Autowired
    public OrderItemServiceImpl(OrderItemRepository orderItemRepository,
                                OrderRepository orderRepository,
                                ProductService productService) {
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    @Override
    public OrderItem createOrderItem(Long orderId, OrderItem orderItem) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));

        Product product = productService.getProductById(orderItem.getProduct().getId());
        if (product.getQuantity() < orderItem.getQuantity()) {
            throw new InsufficientStockException("Not enough stock for product: " + product.getName());
        }
        product.setQuantity(product.getQuantity() - orderItem.getQuantity());
        productService.updateProduct(product.getId(), product);

        orderItem.setOrder(order);
        orderItem.setPricePerUnit(product.getPrice());
        orderItem.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity())));

        order.getOrderItems().add(orderItem);
        orderRepository.save(order); // Save the order with the new item

        return orderItemRepository.save(orderItem);
    }

    @Override
    public OrderItem updateOrderItem(Long itemId, OrderItem updatedItem) {
        OrderItem existingItem = orderItemRepository.findById(itemId)
                .orElseThrow(() -> new OrderItemNotFoundException("OrderItem not found with id: " + itemId));

        Product product = productService.getProductById(existingItem.getProduct().getId());
        product.setQuantity(product.getQuantity() + existingItem.getQuantity());

        if (product.getQuantity() < updatedItem.getQuantity()) {
            throw new InsufficientStockException("Not enough stock for product: " + product.getName());
        }

        product.setQuantity(product.getQuantity() - updatedItem.getQuantity());
        productService.updateProduct(product.getId(), product);

        existingItem.setQuantity(updatedItem.getQuantity());
        existingItem.setPricePerUnit(product.getPrice());
        existingItem.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(updatedItem.getQuantity())));

        return orderItemRepository.save(existingItem);
    }

    @Override
    public void deleteOrderItem(Long itemId) {
        OrderItem orderItem = orderItemRepository.findById(itemId)
                .orElseThrow(() -> new OrderItemNotFoundException("OrderItem not found with id: " + itemId));

        // Restore product stock
        Product product = orderItem.getProduct();
        product.setQuantity(product.getQuantity() + orderItem.getQuantity());
        productService.updateProduct(product.getId(), product);

        // Remove the OrderItem from the Order's list
        Order order = orderItem.getOrder();
        order.getOrderItems().remove(orderItem); // Orphan removal ensures this is deleted
        orderRepository.save(order);
    }

    @Override
    public List<OrderItem> getOrderItemsByOrderId(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));
        return order.getOrderItems();
    }
}
