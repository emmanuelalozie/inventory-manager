package com.example.inventix.service;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.OrderItemNotFoundException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.*;

/**
 * Tests the stock and total bookkeeping in OrderItemServiceImpl with the repositories and ProductService mocked.
 */
class OrderItemServiceTest {

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

    @InjectMocks
    private OrderItemServiceImpl orderItemService;

    private Product product;
    private Order order;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        product = new Product();
        product.setId(1L);
        product.setName("Keyboard");
        product.setSku("KB-1");
        product.setPrice(new BigDecimal("25.00"));
        product.setQuantity(10);

        order = new Order();
        order.setId(1L);
        order.setStatus(OrderStatus.PENDING);

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderItemRepository.save(any(OrderItem.class))).thenAnswer(returnsFirstArg());
    }

    // An item as it arrives from the API: only the product id and the quantity are set.
    private static OrderItem request(Long productId, int quantity) {
        Product ref = new Product();
        ref.setId(productId);
        OrderItem item = new OrderItem();
        item.setProduct(ref);
        item.setQuantity(quantity);
        return item;
    }

    // An item that is already part of the order.
    private OrderItem existingItem(long id, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(id);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPricePerUnit(product.getPrice());
        item.recalculateSubtotal();
        order.addItem(item);
        when(orderItemRepository.findById(id)).thenReturn(Optional.of(item));
        return item;
    }

    @Test
    void createOrderItem_reservesStockAndComputesSubtotalAndOrderTotal() {
        when(productService.adjustStock(1L, -3)).thenReturn(product);

        OrderItem created = orderItemService.createOrderItem(1L, request(1L, 3));

        verify(productService).adjustStock(1L, -3);
        assertThat(created.getProduct()).isSameAs(product);
        assertThat(created.getPricePerUnit()).isEqualByComparingTo("25.00");
        assertThat(created.getSubtotal()).isEqualByComparingTo("75.00");
        assertThat(created.getOrderId()).isEqualTo(1L);
        assertThat(order.getOrderItems()).containsExactly(created);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("75.00");
        verify(orderItemRepository).save(created);
    }

    @Test
    void createOrderItem_addsEachItemToTheOrderTotal() {
        Product cable = new Product();
        cable.setId(2L);
        cable.setPrice(new BigDecimal("4.50"));
        cable.setQuantity(5);
        when(productService.adjustStock(1L, -3)).thenReturn(product);
        when(productService.adjustStock(2L, -2)).thenReturn(cable);

        orderItemService.createOrderItem(1L, request(1L, 3));
        orderItemService.createOrderItem(1L, request(2L, 2));

        assertThat(order.getOrderItems()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("84.00");
    }

    @Test
    void createOrderItem_usesCurrentProductPriceNotClientValues() {
        when(productService.adjustStock(1L, -1)).thenReturn(product);
        OrderItem item = request(1L, 1);
        item.setId(42L);
        item.setPricePerUnit(new BigDecimal("0.01"));

        OrderItem created = orderItemService.createOrderItem(1L, item);

        assertThat(created.getId()).isNull();
        assertThat(created.getPricePerUnit()).isEqualByComparingTo("25.00");
    }

    @Test
    void createOrderItem_throwsInsufficientStock_andLeavesOrderUnchanged() {
        when(productService.adjustStock(1L, -50))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Keyboard'"));

        assertThrows(InsufficientStockException.class, () -> orderItemService.createOrderItem(1L, request(1L, 50)));

        assertThat(order.getOrderItems()).isEmpty();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0");
        verify(orderItemRepository, never()).save(any(OrderItem.class));
    }

    @Test
    void createOrderItem_throwsInvalidState_whenOrderIsNotPending() {
        order.setStatus(OrderStatus.SHIPPED);

        assertThrows(InvalidOrderStateException.class, () -> orderItemService.createOrderItem(1L, request(1L, 1)));
        verify(productService, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void createOrderItem_throwsNotFound_whenOrderIsMissing() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderItemService.createOrderItem(99L, request(1L, 1)));
        verify(productService, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void createOrderItem_rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> orderItemService.createOrderItem(1L, request(1L, 0)));
        verify(productService, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void createOrderItem_rejectsMissingProduct() {
        OrderItem item = new OrderItem();
        item.setQuantity(1);

        assertThrows(IllegalArgumentException.class, () -> orderItemService.createOrderItem(1L, item));
        verify(productService, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void updateOrderItem_takesMoreStock_whenQuantityGrows() {
        OrderItem item = existingItem(5L, 3);
        OrderItem changes = new OrderItem();
        changes.setQuantity(5);

        OrderItem updated = orderItemService.updateOrderItem(5L, changes);

        verify(productService).adjustStock(1L, -2);
        assertThat(updated).isSameAs(item);
        assertThat(updated.getQuantity()).isEqualTo(5);
        assertThat(updated.getSubtotal()).isEqualByComparingTo("125.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("125.00");
    }

    @Test
    void updateOrderItem_returnsStock_whenQuantityShrinks() {
        existingItem(5L, 3);
        OrderItem changes = new OrderItem();
        changes.setQuantity(1);

        orderItemService.updateOrderItem(5L, changes);

        verify(productService).adjustStock(1L, 2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void updateOrderItem_keepsItemUnchanged_whenStockIsInsufficient() {
        OrderItem item = existingItem(5L, 3);
        when(productService.adjustStock(1L, -20))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Keyboard'"));
        OrderItem changes = new OrderItem();
        changes.setQuantity(23);

        assertThrows(InsufficientStockException.class, () -> orderItemService.updateOrderItem(5L, changes));

        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("75.00");
    }

    @Test
    void deleteOrderItem_restoresStockAndRecalculatesTotal() {
        OrderItem item = existingItem(5L, 3);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("75.00");

        orderItemService.deleteOrderItem(5L);

        verify(productService).adjustStock(1L, 3);
        verify(orderItemRepository).delete(item);
        assertThat(order.getOrderItems()).isEmpty();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0");
        assertThat(item.getOrder()).isNull();
    }

    @Test
    void deleteOrderItem_throwsInvalidState_whenOrderIsNotPending() {
        existingItem(5L, 3);
        order.setStatus(OrderStatus.DELIVERED);

        assertThrows(InvalidOrderStateException.class, () -> orderItemService.deleteOrderItem(5L));
        verify(productService, never()).adjustStock(anyLong(), anyInt());
        verify(orderItemRepository, never()).delete(any(OrderItem.class));
        assertThat(order.getOrderItems()).hasSize(1);
    }

    @Test
    void deleteOrderItem_throwsNotFound_whenItemIsMissing() {
        when(orderItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(OrderItemNotFoundException.class, () -> orderItemService.deleteOrderItem(99L));
    }

    @Test
    void getOrderItem_returnsItem_whenItBelongsToTheOrder() {
        OrderItem item = existingItem(5L, 3);

        assertThat(orderItemService.getOrderItem(1L, 5L)).isSameAs(item);
    }

    @Test
    void getOrderItem_throwsNotFound_whenItemBelongsToAnotherOrder() {
        existingItem(5L, 3);
        Order otherOrder = new Order();
        otherOrder.setId(2L);
        when(orderRepository.findById(2L)).thenReturn(Optional.of(otherOrder));

        assertThrows(OrderItemNotFoundException.class, () -> orderItemService.getOrderItem(2L, 5L));
    }
}
