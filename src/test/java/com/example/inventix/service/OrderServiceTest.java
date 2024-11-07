package com.example.inventix.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.ProductService;
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

class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

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
        sampleItem.setProduct(sampleProduct); // Link Product to OrderItem
        sampleItem.setQuantity(2);

        sampleOrder = new Order();
        sampleOrder.setId(1L);
        sampleOrder.setOrderItems(new ArrayList<>(List.of(sampleItem))); // Ensure the list is mutable

        // Debug: Assert to confirm setup is correct
        assertNotNull(sampleItem.getProduct(), "Product should not be null in OrderItem after setup");
    }

    @Test
    void createOrder_ShouldSaveOrder_WhenStockIsSufficient() {
        when(productService.getProductById(sampleProduct.getId())).thenReturn(sampleProduct);
        when(orderRepository.save(any(Order.class))).thenReturn(sampleOrder);

        Order createdOrder = orderService.createOrder(sampleOrder);

        assertNotNull(createdOrder);
        assertEquals(sampleOrder.getOrderItems().size(), createdOrder.getOrderItems().size());
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(productService, times(1)).updateProduct(sampleProduct.getId(), sampleProduct);
    }

    @Test
    void createOrder_ShouldThrowException_WhenStockIsInsufficient() {
        sampleItem.setQuantity(15); // More than available stock
        when(productService.getProductById(sampleProduct.getId())).thenReturn(sampleProduct);

        assertThrows(InsufficientStockException.class, () -> orderService.createOrder(sampleOrder));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void getOrderById_ShouldReturnOrder_WhenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        Order order = orderService.getOrderById(1L);

        assertNotNull(order);
        assertEquals(sampleOrder.getId(), order.getId());
    }

    @Test
    void getOrderById_ShouldThrowException_WhenOrderDoesNotExist() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.getOrderById(1L));
    }

    @Test
    void updateOrder_ShouldUpdateOrder_WhenStockIsSufficient() {
        Order updatedOrder = new Order();
        updatedOrder.setOrderItems(new ArrayList<>(List.of(sampleItem)));
        sampleItem.setQuantity(5);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(productService.getProductById(sampleProduct.getId())).thenReturn(sampleProduct);
        when(orderRepository.save(any(Order.class))).thenReturn(updatedOrder);

        Order result = orderService.updateOrder(1L, updatedOrder);

        assertEquals(updatedOrder.getOrderItems().size(), result.getOrderItems().size());
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(productService, times(2)).updateProduct(sampleProduct.getId(), sampleProduct);
    }

    @Test
    void updateOrder_ShouldThrowException_WhenStockIsInsufficient() {
        // Set up initial order state with lower quantity
        sampleProduct.setQuantity(5); // Available quantity in stock
        sampleItem.setQuantity(3);    // Existing quantity in the order

        // Create a new order request with a higher quantity to simulate an update
        Order updatedOrder = new Order();
        OrderItem updatedItem = new OrderItem();
        updatedItem.setProduct(sampleProduct);
        updatedItem.setQuantity(10); // New requested quantity, which is too high
        updatedOrder.setOrderItems(List.of(updatedItem));

        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));
        when(productService.getProductById(sampleProduct.getId())).thenReturn(sampleProduct);

        // Assert that the InsufficientStockException is thrown
        assertThrows(InsufficientStockException.class, () -> orderService.updateOrder(1L, updatedOrder));
        verify(orderRepository, never()).save(any(Order.class));
    }


    @Test
    void deleteOrder_ShouldRestoreProductQuantities_AndDeleteOrder() {
        // Ensure that findById returns the sampleOrder with the linked sampleItem and sampleProduct
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        // Perform the delete operation
        orderService.deleteOrder(1L);

        // Verify that product quantity is restored
        verify(productService, times(1)).updateProduct(sampleProduct.getId(), sampleProduct);
        // Verify that the order is deleted
        verify(orderRepository, times(1)).delete(sampleOrder);
    }

    @Test
    void deleteOrder_ShouldThrowException_WhenOrderNotFound() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.deleteOrder(1L));
        verify(orderRepository, never()).delete(any(Order.class));
    }

    @Test
    void getAllOrders_ShouldReturnListOfOrders() {
        List<Order> orders = List.of(sampleOrder);
        when(orderRepository.findAll()).thenReturn(orders);

        List<Order> result = orderService.getAllOrders();

        assertEquals(orders.size(), result.size());
        verify(orderRepository, times(1)).findAll();
    }

    @Test
    void updateOrderStatus_ShouldUpdateStatus_WhenOrderExists() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sampleOrder));

        Order result = orderService.updateOrderStatus(1L, OrderStatus.SHIPPED);

        assertEquals(OrderStatus.SHIPPED, result.getStatus());
    }

    @Test
    void updateOrderStatus_ShouldThrowException_WhenOrderNotFound() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.updateOrderStatus(1L, OrderStatus.SHIPPED));
    }
}