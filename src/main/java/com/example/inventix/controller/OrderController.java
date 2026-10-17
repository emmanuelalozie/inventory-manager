package com.example.inventix.controller;

import com.example.inventix.dto.OrderItemRequest;
import com.example.inventix.dto.OrderItemResponse;
import com.example.inventix.dto.OrderRequest;
import com.example.inventix.dto.OrderResponse;
import com.example.inventix.dto.OrderStatusRequest;
import com.example.inventix.dto.QuantityUpdateRequest;
import com.example.inventix.exception.BadRequestException;
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
import java.util.Optional;

// Entities are mapped to DTOs here, after the service transaction has committed. Orders come back from
// OrderRepository with their items and products already loaded, so nothing is lazy-loaded during mapping.
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
    public List<OrderResponse> getAllOrders(@RequestParam(required = false) OrderStatus status) {
        List<Order> orders = status == null ? orderService.getAllOrders() : orderService.getOrdersByStatus(status);
        return OrderResponse.fromAll(orders);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(OrderResponse.from(orderService.getOrderById(id)));
    }

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * Creates an order. With an Idempotency-Key header, repeating the request (e.g. after a timeout) returns the
     * order created the first time with 200 instead of creating and reserving stock for a second one.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.trim();
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > Order.MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new BadRequestException(IDEMPOTENCY_KEY_HEADER + " must be 1 to "
                        + Order.MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
            Optional<Order> existing = orderService.findOrderByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return ResponseEntity.ok(OrderResponse.from(existing.get()));
            }
        }

        Order newOrder = new Order();
        newOrder.setIdempotencyKey(idempotencyKey);
        OrderResponse createdOrder = OrderResponse.from(orderService.createOrderWithItems(newOrder, request.toItems()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdOrder.id())
                .toUri();
        return ResponseEntity.created(location).body(createdOrder);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> updateOrder(
            @PathVariable Long id,
            @Valid @RequestBody OrderRequest request) {
        Order orderDetails = new Order();
        orderDetails.setOrderItems(request.toItems());
        return ResponseEntity.ok(OrderResponse.from(orderService.updateOrder(id, orderDetails)));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusRequest request) {
        return ResponseEntity.ok(OrderResponse.from(orderService.updateOrderStatus(id, request.status())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{orderId}/items")
    public List<OrderItemResponse> getOrderItems(@PathVariable Long orderId) {
        return OrderItemResponse.fromAll(orderItemService.getOrderItemsByOrderId(orderId));
    }

    @PostMapping("/{orderId}/items")
    public ResponseEntity<OrderItemResponse> addOrderItem(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderItemRequest request) {
        OrderItemResponse createdItem = OrderItemResponse.from(orderItemService.createOrderItem(orderId, request.toEntity()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{itemId}")
                .buildAndExpand(createdItem.id())
                .toUri();
        return ResponseEntity.created(location).body(createdItem);
    }

    @PutMapping("/{orderId}/items/{itemId}")
    public ResponseEntity<OrderItemResponse> updateOrderItem(
            @PathVariable Long orderId,
            @PathVariable Long itemId,
            @Valid @RequestBody QuantityUpdateRequest request) {
        orderItemService.getOrderItem(orderId, itemId); // 404 if the item isn't part of this order
        OrderItem changes = new OrderItem();
        changes.setQuantity(request.quantity());
        return ResponseEntity.ok(OrderItemResponse.from(orderItemService.updateOrderItem(itemId, changes)));
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
