package com.example.inventix.service;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.*;

class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

    @Mock
    private OrderItemService orderItemService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Order sampleOrder;
    private OrderItem sampleItem;
    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        sampleProduct = new Product();
        sampleProduct.setId(1L);
        sampleProduct.setName("Sample Product");
        sampleProduct.setPrice(BigDecimal.valueOf(50));
        sampleProduct.setQuantity(10);

        sampleItem = new OrderItem();
        sampleItem.setId(1L); // Ensure the ID is set
        sampleItem.setProduct(sampleProduct);
        sampleItem.setQuantity(2);

        sampleOrder = new Order();
        sampleOrder.setId(1L);
        sampleOrder.setOrderItems(new ArrayList<>(List.of(sampleItem)));
    }

    @Test
    void createOrder_ShouldSaveOrder_WhenNoItemsAreAdded() {
        Order newOrder = new Order();
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1L); // Simulate order ID being set by the database
            return order;
        });

        Order createdOrder = orderService.createOrder(newOrder);

        assertNotNull(createdOrder);
        assertTrue(createdOrder.getOrderItems().isEmpty());
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    void createOrderWithItems_ShouldSaveOrderAndItems_WhenStockIsSufficient() {
        Order newOrder = new Order();
        OrderItem newItem = new OrderItem();
        newItem.setProduct(sampleProduct);
        newItem.setQuantity(2);

        // Mock save to assign an ID to the order
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1L); // Simulate order ID being set by the database
            return order;
        });

        // Mock findById to return the saved order
        when(orderRepository.findById(1L)).thenReturn(Optional.of(newOrder));

        // Mock createOrderItem to return the created item
        when(orderItemService.createOrderItem(1L, newItem)).thenReturn(newItem);

        Order createdOrder = orderService.createOrderWithItems(newOrder, List.of(newItem));

        assertNotNull(createdOrder);
        assertEquals(1L, createdOrder.getId());
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(orderItemService, times(1)).createOrderItem(1L, newItem);
    }


    @Test
    void getOrderById_ShouldReturnOrder_WhenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        Order order = orderService.getOrderById(1L);

        assertNotNull(order);
        assertEquals(sampleOrder.getId(), order.getId());
        verify(orderRepository, times(1)).findById(1L);
    }

    @Test
    void getOrderById_ShouldThrowException_WhenOrderDoesNotExist() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.getOrderById(1L));
        verify(orderRepository, times(1)).findById(1L);
    }

    @Test
    void addOrderItems_ShouldAddItems_WhenCalledWithValidOrderAndItems() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderItemService.createOrderItem(anyLong(), any(OrderItem.class))).thenReturn(sampleItem);

        Order updatedOrder = orderService.addOrderItems(1L, List.of(sampleItem));

        assertNotNull(updatedOrder);
        verify(orderItemService, times(1)).createOrderItem(1L, sampleItem);
        verify(orderRepository, times(1)).findById(1L);
    }

    @Test
    void deleteOrder_ShouldDeleteOrderAndRestoreStock_WhenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        orderService.deleteOrder(1L);

        verify(orderItemService, times(1)).deleteOrderItem(sampleItem.getId());
        verify(orderRepository, times(1)).delete(sampleOrder);
    }

    @Test
    void deleteOrder_ShouldThrowException_WhenOrderNotFound() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.deleteOrder(1L));
        verify(orderRepository, times(1)).findById(1L);
        verify(orderRepository, never()).delete(any(Order.class));
    }

    @Test
    void updateOrder_ShouldUpdateOrderWithNewItems_WhenStockIsSufficient() {
        // Setup: Create an updated order with new items
        Order updatedOrder = new Order();
        updatedOrder.setId(1L);

        OrderItem updatedItem = new OrderItem();
        updatedItem.setId(2L);
        updatedItem.setProduct(sampleProduct);
        updatedItem.setQuantity(3);
        updatedOrder.setOrderItems(List.of(updatedItem));

        // Mock findById to return the existing order
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        // Mock createOrderItem to return the new item
        when(orderItemService.createOrderItem(1L, updatedItem)).thenReturn(updatedItem);

        // Mock save to simulate persistence of the updated order
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setOrderItems(List.of(updatedItem)); // Simulate the updated orderItems list
            return order;
        });

        // Execute: Call the updateOrder method
        Order result = orderService.updateOrder(1L, updatedOrder);

        // Assertions: Verify the updated state
        assertNotNull(result, "The updated order should not be null.");
        assertEquals(1, result.getOrderItems().size(), "The order should contain exactly 1 order item.");
        assertEquals(updatedItem.getId(), result.getOrderItems().get(0).getId(), "The order item ID should match the updated item.");

        // Verifications: Ensure mocks were invoked as expected
        verify(orderRepository, times(1)).findById(1L);
        verify(orderItemService, times(1)).createOrderItem(1L, updatedItem);
        verify(orderRepository, times(1)).save(any(Order.class));
    }


    @Test
    void updateOrder_ShouldThrowException_WhenOrderDoesNotExist() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.updateOrder(1L, new Order()));
        verify(orderRepository, times(1)).findById(1L);
    }

    @Test
    void getAllOrders_ShouldReturnListOfOrders_WhenOrdersExist() {
        when(orderRepository.findAll()).thenReturn(List.of(sampleOrder));

        List<Order> orders = orderService.getAllOrders();

        assertNotNull(orders);
        assertEquals(1, orders.size());
        verify(orderRepository, times(1)).findAll();
    }

    @Test
    void updateOrderStatus_ShouldUpdateStatus_WhenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setStatus(OrderStatus.SHIPPED);
            return order;
        });

        Order result = orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);

        assertNotNull(result);
        assertEquals(OrderStatus.SHIPPED, result.getStatus());
        verify(orderRepository, times(1)).save(any(Order.class));
    }

    @Test
    void updateOrderStatus_ShouldThrowException_WhenOrderDoesNotExist() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.updateOrderStatus(1L, OrderStatus.SHIPPED));
        verify(orderRepository, times(1)).findById(1L);
    }

    @Test
    void createOrder_ShouldStartAsEmptyPendingOrderWithZeroTotal_WhateverTheInput() {
        Order input = new Order();
        input.setId(42L);
        input.setStatus(OrderStatus.DELIVERED);
        input.setTotalAmount(new BigDecimal("999.99"));
        input.setOrderItems(new ArrayList<>(List.of(sampleItem)));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            assertNull(order.getId(), "createOrder must insert a new order, not overwrite order 42");
            order.setId(1L);
            return order;
        });

        Order created = orderService.createOrder(input);

        assertEquals(1L, created.getId());
        assertEquals(OrderStatus.PENDING, created.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getTotalAmount()));
        assertTrue(created.getOrderItems().isEmpty(), "Items must go through OrderItemService so stock is reserved");
    }

    @Test
    void createOrderWithItems_ShouldReturnOrderWithTotalOfItsItems() {
        Order newOrder = new Order();
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1L);
            return order;
        });
        when(orderRepository.findById(1L)).thenReturn(Optional.of(newOrder));
        // Act like OrderItemServiceImpl: price the item and attach it, which recalculates the order total.
        when(orderItemService.createOrderItem(eq(1L), any(OrderItem.class))).thenAnswer(invocation -> {
            OrderItem item = invocation.getArgument(1);
            item.setPricePerUnit(item.getProduct().getPrice());
            item.recalculateSubtotal();
            newOrder.addItem(item);
            return item;
        });
        OrderItem first = new OrderItem();
        first.setProduct(sampleProduct);
        first.setQuantity(2);
        OrderItem second = new OrderItem();
        second.setProduct(sampleProduct);
        second.setQuantity(1);

        Order created = orderService.createOrderWithItems(newOrder, List.of(first, second));

        assertEquals(OrderStatus.PENDING, created.getStatus());
        assertEquals(2, created.getOrderItems().size());
        assertEquals(0, new BigDecimal("150").compareTo(created.getTotalAmount()));
    }

    @Test
    void createOrderWithItems_ShouldPropagateInsufficientStock() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(1L);
            return order;
        });
        when(orderItemService.createOrderItem(eq(1L), any(OrderItem.class)))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Sample Product'"));
        OrderItem tooMany = new OrderItem();
        tooMany.setProduct(sampleProduct);
        tooMany.setQuantity(11);

        assertThrows(InsufficientStockException.class,
                () -> orderService.createOrderWithItems(new Order(), List.of(tooMany)));
    }

    @Test
    void updateOrder_ShouldThrowInvalidState_WhenOrderIsNotPending() {
        sampleOrder.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(InvalidOrderStateException.class, () -> orderService.updateOrder(1L, new Order()));
        verify(orderItemService, never()).deleteOrderItem(anyLong());
        verify(orderItemService, never()).createOrderItem(anyLong(), any(OrderItem.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void updateOrder_ShouldRemoveOldItemsBeforeAddingNewOnes() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());
        OrderItem replacement = new OrderItem();
        replacement.setProduct(sampleProduct);
        replacement.setQuantity(4);
        Order changes = new Order();
        changes.setOrderItems(List.of(replacement));

        orderService.updateOrder(1L, changes);

        // Old stock is given back first, then the new item reserves stock, each exactly once.
        var sequence = inOrder(orderItemService);
        sequence.verify(orderItemService).deleteOrderItem(sampleItem.getId());
        sequence.verify(orderItemService).createOrderItem(1L, replacement);
        verify(orderItemService, times(1)).createOrderItem(anyLong(), any(OrderItem.class));
    }

    @Test
    void deleteOrder_ShouldNotRestoreStock_WhenOrderWasShipped() {
        sampleOrder.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        orderService.deleteOrder(1L);

        verify(orderItemService, never()).deleteOrderItem(anyLong());
        verify(productService, never()).adjustStock(anyLong(), anyInt(), any(), any(), any());
        verify(orderRepository, times(1)).delete(sampleOrder);
    }

    @Test
    void updateOrderStatus_ToCancelled_ShouldRestoreStockOfEveryItem() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

        Order result = orderService.updateOrderStatus(1L, OrderStatus.CANCELLED);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productService, times(1)).adjustStock(sampleProduct.getId(), sampleItem.getQuantity(),
                MovementReason.CANCEL, "Order #1 cancelled", 1L);
        assertEquals(1, result.getOrderItems().size(), "Cancelled orders keep their items as a record");
    }

    @Test
    void updateOrderStatus_ToShipped_ShouldNotTouchStock() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

        orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);

        verify(productService, never()).adjustStock(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    void updateOrderStatus_ShouldFollowAllowedTransitions() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(returnsFirstArg());

        orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);
        Order delivered = orderService.updateOrderStatus(1L, OrderStatus.DELIVERED);

        assertEquals(OrderStatus.DELIVERED, delivered.getStatus());
    }

    @Test
    void updateOrderStatus_ShouldThrowInvalidState_ForForbiddenTransition() {
        sampleOrder.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        assertThrows(InvalidOrderStateException.class,
                () -> orderService.updateOrderStatus(1L, OrderStatus.CANCELLED));
        assertEquals(OrderStatus.DELIVERED, sampleOrder.getStatus());
        verify(orderRepository, never()).save(any(Order.class));
        verify(productService, never()).adjustStock(anyLong(), anyInt(), any(), any(), any());
    }

    @Test
    void updateOrderStatus_ShouldNotCancelTwice() {
        sampleOrder.setStatus(OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        // Same status: nothing to do, and the stock must not be restored a second time.
        Order result = orderService.updateOrderStatus(1L, OrderStatus.CANCELLED);

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(productService, never()).adjustStock(anyLong(), anyInt(), any(), any(), any());
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void getOrdersByStatus_ShouldQueryRepository() {
        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(sampleOrder));

        List<Order> orders = orderService.getOrdersByStatus(OrderStatus.PENDING);

        assertEquals(List.of(sampleOrder), orders);
    }

    @Test
    void orderStatus_TransitionRules() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.SHIPPED));
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED));
        assertFalse(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.PENDING));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PENDING));
    }
}
