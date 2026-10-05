package com.example.inventix.service.impl;

import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.OrderItemNotFoundException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
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
        Order order = findOrder(orderId);
        ensureEditable(order);

        if (orderItem.getProduct() == null || orderItem.getProduct().getId() == null) {
            throw new IllegalArgumentException("productId is required");
        }
        requirePositiveQuantity(orderItem.getQuantity());

        // Reserve the stock; throws InsufficientStockException if there isn't enough.
        Product product = productService.adjustStock(orderItem.getProduct().getId(), -orderItem.getQuantity());

        orderItem.setId(null);
        orderItem.setProduct(product);
        orderItem.setPricePerUnit(product.getPrice());
        orderItem.recalculateSubtotal();
        order.addItem(orderItem); // also recalculates the order total

        return orderItemRepository.save(orderItem);
    }

    @Override
    public OrderItem updateOrderItem(Long itemId, OrderItem updatedItem) {
        OrderItem existingItem = findItem(itemId);
        Order order = existingItem.getOrder();
        ensureEditable(order);
        requirePositiveQuantity(updatedItem.getQuantity());

        // Positive delta returns stock to the product, negative delta takes more.
        int delta = existingItem.getQuantity() - updatedItem.getQuantity();
        if (delta != 0) {
            productService.adjustStock(existingItem.getProduct().getId(), delta);
        }

        // The unit price stays as it was when the item was added to the order.
        existingItem.setQuantity(updatedItem.getQuantity());
        existingItem.recalculateSubtotal();
        order.recalculateTotal();

        return orderItemRepository.save(existingItem);
    }

    @Override
    public void deleteOrderItem(Long itemId) {
        OrderItem orderItem = findItem(itemId);
        Order order = orderItem.getOrder();
        ensureEditable(order);

        // Restore product stock
        productService.adjustStock(orderItem.getProduct().getId(), orderItem.getQuantity());

        // Unlink the item (this also recalculates the order total), then delete it explicitly:
        // orphan removal misses items that were added in the same session and not flushed yet.
        order.removeItem(orderItem);
        orderItemRepository.delete(orderItem);
    }

    @Override
    public List<OrderItem> getOrderItemsByOrderId(Long orderId) {
        return new ArrayList<>(findOrder(orderId).getOrderItems());
    }

    @Override
    public OrderItem getOrderItem(Long orderId, Long itemId) {
        findOrder(orderId);
        OrderItem item = findItem(itemId);
        if (item.getOrder() == null || !orderId.equals(item.getOrder().getId())) {
            throw new OrderItemNotFoundException("OrderItem " + itemId + " not found in order " + orderId);
        }
        return item;
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));
    }

    private OrderItem findItem(Long itemId) {
        return orderItemRepository.findById(itemId)
                .orElseThrow(() -> new OrderItemNotFoundException("OrderItem not found with id: " + itemId));
    }

    // Only pending orders hold reserved stock, so only they can have their items changed.
    private void ensureEditable(Order order) {
        if (order.getStatus() != null && order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Order " + order.getId() + " is " + order.getStatus()
                    + "; only PENDING orders can be changed");
        }
    }

    private void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
    }
}
