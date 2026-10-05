package com.example.inventix.controller;

import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.OrderItemNotFoundException;
import com.example.inventix.exception.OrderNotFoundException;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.service.OrderItemService;
import com.example.inventix.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for OrderController, with OrderService and OrderItemService mocked.
 * GlobalExceptionHandler is part of the MVC slice, so error status codes are tested too.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private OrderItemService orderItemService;

    private static Product product(long id, String price, int quantity) {
        return new Product(id, "Widget " + id, "W-" + id, "A widget", new BigDecimal(price), quantity, null, null);
    }

    private static OrderItem item(long id, Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.setId(id);
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPricePerUnit(product.getPrice());
        item.recalculateSubtotal();
        return item;
    }

    // Builds an order the way the service does: addItem links both sides and recalculates the total.
    private static Order order(long id, OrderStatus status, OrderItem... items) {
        Order order = new Order();
        order.setId(id);
        order.setStatus(status);
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return order;
    }

    // --- orders ---

    @Test
    void getAllOrders_returnsOrders() throws Exception {
        when(orderService.getAllOrders()).thenReturn(List.of(order(1L, OrderStatus.PENDING)));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(orderService, never()).getOrdersByStatus(any());
    }

    @Test
    void getAllOrders_filtersByStatus() throws Exception {
        when(orderService.getOrdersByStatus(OrderStatus.SHIPPED)).thenReturn(List.of(order(2L, OrderStatus.SHIPPED)));

        mockMvc.perform(get("/api/orders").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].status").value("SHIPPED"));

        verify(orderService, never()).getAllOrders();
    }

    @Test
    void getAllOrders_returns400_forUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/orders").param("status", "LOST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrderById_serializesItemsWithoutRecursion() throws Exception {
        Order order = order(1L, OrderStatus.PENDING, item(10L, product(1L, "50.00", 8), 2));
        when(orderService.getOrderById(1L)).thenReturn(order);

        mockMvc.perform(get("/api/orders/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(100.0))
                .andExpect(jsonPath("$.orderItems", hasSize(1)))
                .andExpect(jsonPath("$.orderItems[0].id").value(10))
                .andExpect(jsonPath("$.orderItems[0].orderId").value(1))
                .andExpect(jsonPath("$.orderItems[0].order").doesNotExist())
                .andExpect(jsonPath("$.orderItems[0].product.id").value(1))
                .andExpect(jsonPath("$.orderItems[0].quantity").value(2))
                .andExpect(jsonPath("$.orderItems[0].pricePerUnit").value(50.0))
                .andExpect(jsonPath("$.orderItems[0].subtotal").value(100.0));
    }

    @Test
    void getOrderById_returns404_whenOrderIsMissing() throws Exception {
        when(orderService.getOrderById(99L)).thenThrow(new OrderNotFoundException("Order not found with id: 99"));

        mockMvc.perform(get("/api/orders/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Order not found with id: 99"))
                .andExpect(jsonPath("$.path").value("/api/orders/99"));
    }

    @Test
    void createOrder_returns201WithLocation() throws Exception {
        Order created = order(1L, OrderStatus.PENDING, item(10L, product(1L, "50.00", 8), 2));
        when(orderService.createOrderWithItems(any(Order.class), anyList())).thenReturn(created);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/orders/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(100.0))
                .andExpect(jsonPath("$.orderItems[0].orderId").value(1));

        verify(orderService).createOrderWithItems(any(Order.class), argThat(items -> items.size() == 1
                && items.get(0).getProduct().getId() == 1L
                && items.get(0).getQuantity() == 2));
    }

    @Test
    void createOrder_withoutItems_createsEmptyOrder() throws Exception {
        when(orderService.createOrderWithItems(any(Order.class), anyList())).thenReturn(order(3L, OrderStatus.PENDING));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andExpect(jsonPath("$.orderItems", hasSize(0)));

        verify(orderService).createOrderWithItems(any(Order.class), argThat(List::isEmpty));
    }

    @Test
    void createOrder_returns400_whenItemQuantityIsNotPositive() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items[0].quantity"));

        verify(orderService, never()).createOrderWithItems(any(), anyList());
    }

    @Test
    void createOrder_returns400_whenItemProductIdIsMissing() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"quantity\":2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items[0].productId"));

        verify(orderService, never()).createOrderWithItems(any(), anyList());
    }

    @Test
    void createOrder_returns409_whenStockIsInsufficient() throws Exception {
        when(orderService.createOrderWithItems(any(Order.class), anyList()))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Widget 1'"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":500}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void updateOrder_replacesItems() throws Exception {
        Order updated = order(1L, OrderStatus.PENDING, item(11L, product(2L, "10.00", 5), 3));
        when(orderService.updateOrder(eq(1L), any(Order.class))).thenReturn(updated);

        mockMvc.perform(put("/api/orders/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":2,\"quantity\":3}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(30.0))
                .andExpect(jsonPath("$.orderItems[0].product.id").value(2));

        verify(orderService).updateOrder(eq(1L), argThat(details -> details.getOrderItems().size() == 1
                && details.getOrderItems().get(0).getQuantity() == 3));
    }

    @Test
    void updateOrder_returns409_whenOrderIsNotPending() throws Exception {
        when(orderService.updateOrder(eq(1L), any(Order.class)))
                .thenThrow(new InvalidOrderStateException("Order 1 is SHIPPED; only PENDING orders can be changed"));

        mockMvc.perform(put("/api/orders/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateOrder_returns404_whenOrderIsMissing() throws Exception {
        when(orderService.updateOrder(eq(99L), any(Order.class)))
                .thenThrow(new OrderNotFoundException("Order not found with id: 99"));

        mockMvc.perform(put("/api/orders/{id}", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOrder_returns204() throws Exception {
        mockMvc.perform(delete("/api/orders/{id}", 1L))
                .andExpect(status().isNoContent());

        verify(orderService).deleteOrder(1L);
    }

    @Test
    void deleteOrder_returns404_whenOrderIsMissing() throws Exception {
        doThrow(new OrderNotFoundException("Order not found with id: 99")).when(orderService).deleteOrder(99L);

        mockMvc.perform(delete("/api/orders/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    // --- order status ---

    @Test
    void updateOrderStatus_returnsUpdatedOrder() throws Exception {
        when(orderService.updateOrderStatus(1L, OrderStatus.SHIPPED)).thenReturn(order(1L, OrderStatus.SHIPPED));

        mockMvc.perform(patch("/api/orders/{id}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        verify(orderService).updateOrderStatus(1L, OrderStatus.SHIPPED);
    }

    @Test
    void updateOrderStatus_returns409_forInvalidTransition() throws Exception {
        when(orderService.updateOrderStatus(1L, OrderStatus.PENDING))
                .thenThrow(new InvalidOrderStateException("Cannot change order 1 from DELIVERED to PENDING"));

        mockMvc.perform(patch("/api/orders/{id}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot change order 1 from DELIVERED to PENDING"));
    }

    @Test
    void updateOrderStatus_returns400_whenStatusIsMissing() throws Exception {
        mockMvc.perform(patch("/api/orders/{id}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("status"));

        verify(orderService, never()).updateOrderStatus(anyLong(), any());
    }

    @Test
    void updateOrderStatus_returns400_forUnknownStatus() throws Exception {
        mockMvc.perform(patch("/api/orders/{id}/status", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOST\"}"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).updateOrderStatus(anyLong(), any());
    }

    @Test
    void updateOrderStatus_returns404_whenOrderIsMissing() throws Exception {
        when(orderService.updateOrderStatus(99L, OrderStatus.SHIPPED))
                .thenThrow(new OrderNotFoundException("Order not found with id: 99"));

        mockMvc.perform(patch("/api/orders/{id}/status", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isNotFound());
    }

    // --- order items ---

    @Test
    void getOrderItems_returnsItems() throws Exception {
        OrderItem item = item(10L, product(1L, "5.00", 9), 1);
        order(1L, OrderStatus.PENDING, item);
        when(orderItemService.getOrderItemsByOrderId(1L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/orders/{orderId}/items", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value(1));
    }

    @Test
    void addOrderItem_returns201WithLocation() throws Exception {
        OrderItem created = item(10L, product(3L, "5.00", 9), 1);
        order(1L, OrderStatus.PENDING, created); // links the item to order 1
        when(orderItemService.createOrderItem(eq(1L), any(OrderItem.class))).thenReturn(created);

        mockMvc.perform(post("/api/orders/{orderId}/items", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":3,\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/orders/1/items/10")))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.orderId").value(1))
                .andExpect(jsonPath("$.product.id").value(3))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.subtotal").value(5.0));

        verify(orderItemService).createOrderItem(eq(1L), argThat(item -> item.getProduct().getId() == 3L
                && item.getQuantity() == 1));
    }

    @Test
    void addOrderItem_returns400_whenQuantityIsNotPositive() throws Exception {
        mockMvc.perform(post("/api/orders/{orderId}/items", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":3,\"quantity\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"));

        verify(orderItemService, never()).createOrderItem(anyLong(), any());
    }

    @Test
    void addOrderItem_returns409_whenStockIsInsufficient() throws Exception {
        when(orderItemService.createOrderItem(eq(1L), any(OrderItem.class)))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Widget 3'"));

        mockMvc.perform(post("/api/orders/{orderId}/items", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":3,\"quantity\":100}"))
                .andExpect(status().isConflict());
    }

    @Test
    void addOrderItem_returns404_whenOrderIsMissing() throws Exception {
        when(orderItemService.createOrderItem(eq(99L), any(OrderItem.class)))
                .thenThrow(new OrderNotFoundException("Order not found with id: 99"));

        mockMvc.perform(post("/api/orders/{orderId}/items", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":3,\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateOrderItem_changesQuantity() throws Exception {
        Product product = product(3L, "5.00", 9);
        OrderItem existing = item(10L, product, 1);
        OrderItem updated = item(10L, product, 3);
        order(1L, OrderStatus.PENDING, updated);
        when(orderItemService.getOrderItem(1L, 10L)).thenReturn(existing);
        when(orderItemService.updateOrderItem(eq(10L), any(OrderItem.class))).thenReturn(updated);

        mockMvc.perform(put("/api/orders/{orderId}/items/{itemId}", 1L, 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.subtotal").value(15.0));

        verify(orderItemService).updateOrderItem(eq(10L), argThat(changes -> changes.getQuantity() == 3));
    }

    @Test
    void deleteOrderItem_returns204() throws Exception {
        when(orderItemService.getOrderItem(1L, 10L)).thenReturn(item(10L, product(3L, "5.00", 9), 1));

        mockMvc.perform(delete("/api/orders/{orderId}/items/{itemId}", 1L, 10L))
                .andExpect(status().isNoContent());

        verify(orderItemService).deleteOrderItem(10L);
    }

    @Test
    void deleteOrderItem_returns404_whenItemBelongsToAnotherOrder() throws Exception {
        when(orderItemService.getOrderItem(2L, 10L))
                .thenThrow(new OrderItemNotFoundException("OrderItem 10 not found in order 2"));

        mockMvc.perform(delete("/api/orders/{orderId}/items/{itemId}", 2L, 10L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("OrderItem 10 not found in order 2"));

        verify(orderItemService, never()).deleteOrderItem(anyLong());
    }

    @Test
    void deleteOrderItem_returns409_whenOrderIsNotPending() throws Exception {
        when(orderItemService.getOrderItem(1L, 10L)).thenReturn(item(10L, product(3L, "5.00", 9), 1));
        doThrow(new InvalidOrderStateException("Order 1 is SHIPPED; only PENDING orders can be changed"))
                .when(orderItemService).deleteOrderItem(10L);

        mockMvc.perform(delete("/api/orders/{orderId}/items/{itemId}", 1L, 10L))
                .andExpect(status().isConflict());
    }
}
