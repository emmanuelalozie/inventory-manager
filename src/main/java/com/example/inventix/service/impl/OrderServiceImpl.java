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
        // Retrieve the existing order from the repository
        Order orderToUpdate = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));

        BigDecimal totalAmount = BigDecimal.ZERO;

        // Clear current items to update with new items
        orderToUpdate.getOrderItems().clear();

        for (OrderItem newItem : order.getOrderItems()) {
            Product product = productService.getProductById(newItem.getProduct().getId());

            // Find the matching OrderItem in the existing order (if it exists)
            Optional<OrderItem> existingItemOpt = orderToUpdate.getOrderItems().stream()
                    .filter(item -> item.getProduct().getId().equals(newItem.getProduct().getId()))
                    .findFirst();

            int existingQuantity = existingItemOpt.map(OrderItem::getQuantity).orElse(0);
            int newQuantity = newItem.getQuantity();
            int quantityDifference = newQuantity - existingQuantity;

            // If the quantity has increased, check stock availability
            if (quantityDifference > 0) {
                if (product.getQuantity() < quantityDifference) {
                    throw new InsufficientStockException("Not enough stock for product: " + product.getName());
                }
                product.setQuantity(product.getQuantity() - quantityDifference);
            }
            // If the quantity has decreased, return the difference to stock
            else if (quantityDifference < 0) {
                product.setQuantity(product.getQuantity() - quantityDifference); // Adds back the stock
            }

            // Update product stock and save
            productService.updateProduct(product.getId(), product);

            // Set up new item details
            newItem.setOrder(orderToUpdate);
            newItem.setPricePerUnit(product.getPrice());
            newItem.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(newItem.getQuantity())));
            totalAmount = totalAmount.add(newItem.getSubtotal());

            // Add updated item to the order
            orderToUpdate.getOrderItems().add(newItem);
        }

        orderToUpdate.setTotalAmount(totalAmount);
        return orderRepository.save(orderToUpdate);
    }

    //TODO When deleting an order, check if order is completed. If so
    // - leave product quantity as is. IF not, append products in order
    // - items back into product item's quantity
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
