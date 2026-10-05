package com.example.inventix.controller;

import com.example.inventix.dto.OrderItemRequest;
import com.example.inventix.dto.OrderRequest;
import com.example.inventix.dto.OrderStatusRequest;
import com.example.inventix.dto.QuantityUpdateRequest;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderItemService orderItemService;

    @Autowired
    public OrderController(OrderService orderService, OrderItemService orderItemService) {
        this.orderService = orderService;
        this.orderItemService = orderItemService;
    }

    @GetMapping
    public List<Order> getAllOrders(@RequestParam(required = false) OrderStatus status) {
        return status == null ? orderService.getAllOrders() : orderService.getOrdersByStatus(status);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        Order order = orderService.getOrderById(id);
        return ResponseEntity.ok(order);
    }

    @PostMapping
    public ResponseEntity<Order> createOrder(@Valid @RequestBody OrderRequest request) {
        Order createdOrder = orderService.createOrderWithItems(new Order(), request.toItems());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdOrder.getId())
                .toUri();
        return ResponseEntity.created(location).body(createdOrder);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Order> updateOrder(
            @PathVariable Long id,
            @Valid @RequestBody OrderRequest request) {
        Order orderDetails = new Order();
        orderDetails.setOrderItems(request.toItems());
        Order updatedOrder = orderService.updateOrder(id, orderDetails);
        return ResponseEntity.ok(updatedOrder);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusRequest request) {
        Order updatedOrder = orderService.updateOrderStatus(id, request.status());
        return ResponseEntity.ok(updatedOrder);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orderId}/items")
    public List<OrderItem> getOrderItems(@PathVariable Long orderId) {
        return orderItemService.getOrderItemsByOrderId(orderId);
    }

    @PostMapping("/{orderId}/items")
    public ResponseEntity<OrderItem> addOrderItem(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderItemRequest request) {
        OrderItem createdItem = orderItemService.createOrderItem(orderId, request.toEntity());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{itemId}")
                .buildAndExpand(createdItem.getId())
                .toUri();
        return ResponseEntity.created(location).body(createdItem);
    }

    @PutMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<OrderItem> updateOrderItem(
            @PathVariable Long orderId,
            @PathVariable Long itemId,
            @Valid @RequestBody QuantityUpdateRequest request) {
        orderItemService.getOrderItem(orderId, itemId); // 404 if the item isn't part of this order
        OrderItem changes = new OrderItem();
        changes.setQuantity(request.quantity());
        OrderItem updatedItem = orderItemService.updateOrderItem(itemId, changes);
        return ResponseEntity.ok(updatedItem);
    }

    @DeleteMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<Void> deleteOrderItem(
            @PathVariable Long orderId,
            @PathVariable Long itemId) {
        orderItemService.getOrderItem(orderId, itemId); // 404 if the item isn't part of this order
        orderItemService.deleteOrderItem(itemId);
        return ResponseEntity.noContent().build();
    }
}
