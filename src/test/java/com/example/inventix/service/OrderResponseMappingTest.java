package com.example.inventix.service;

import com.example.inventix.dto.OrderItemRequest;
import com.example.inventix.dto.OrderItemResponse;
import com.example.inventix.dto.OrderResponse;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The controllers map orders to DTOs after the service transaction has ended (open-in-view is off).
 * This test does the same: the test itself runs without a transaction, so every service call commits
 * on its own and a lazily loaded orderItems list would throw LazyInitializationException during mapping.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, OrderItemServiceImpl.class, OrderServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderResponseMappingTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private Long keyboardId; // 25.00 each
    private Long cableId;    // 4.50 each
    private Long orderId;    // 3 keyboards + 2 cables

    @BeforeEach
    void setUp() {
        keyboardId = productRepository.save(product("Keyboard", "MAP-KB-1", "25.00", 10)).getId();
        cableId = productRepository.save(product("Cable", "MAP-CB-1", "4.50", 5)).getId();
        orderId = orderService.createOrderWithItems(new Order(), List.of(
                new OrderItemRequest(keyboardId, 3).toEntity(),
                new OrderItemRequest(cableId, 2).toEntity())).getId();
    }

    // Nothing is rolled back here, so remove what the test wrote (items go with their order;
    // stock movements reference the products, so they go first).
    @AfterEach
    void tearDown() {
        stockMovementRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    private static Product product(String name, String sku, String price, int quantity) {
        Product product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setPrice(new BigDecimal(price));
        product.setQuantity(quantity);
        return product;
    }

    private void assertKeyboardsAndCables(OrderResponse response) {
        assertThat(response.id()).isEqualTo(orderId);
        assertThat(response.totalAmount()).isEqualByComparingTo("84.00");
        assertThat(response.orderItems()).hasSize(2);
        assertThat(response.orderItems()).extracting(OrderItemResponse::orderId).containsOnly(orderId);
        assertThat(response.orderItems())
                .extracting(OrderItemResponse::productId, OrderItemResponse::productName, OrderItemResponse::productSku,
                        OrderItemResponse::quantity)
                .containsExactlyInAnyOrder(
                        tuple(keyboardId, "Keyboard", "MAP-KB-1", 3),
                        tuple(cableId, "Cable", "MAP-CB-1", 2));
    }

    @Test
    void getOrderById_mapsItemsOutsideTheTransaction() {
        assertKeyboardsAndCables(OrderResponse.from(orderService.getOrderById(orderId)));
    }

    @Test
    void getAllOrders_mapsItemsOutsideTheTransaction() {
        List<OrderResponse> responses = OrderResponse.fromAll(orderService.getAllOrders());

        assertThat(responses).hasSize(1);
        assertKeyboardsAndCables(responses.get(0));
    }

    @Test
    void getOrdersByStatus_mapsItemsOutsideTheTransaction() {
        List<OrderResponse> responses = OrderResponse.fromAll(orderService.getOrdersByStatus(OrderStatus.PENDING));

        assertThat(responses).hasSize(1);
        assertKeyboardsAndCables(responses.get(0));
    }

    @Test
    void updateOrderStatus_mapsItemsOutsideTheTransaction() {
        OrderResponse response = OrderResponse.from(orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertKeyboardsAndCables(response); // cancelled orders keep their items as a record
    }

    @Test
    void getOrderItemsByOrderId_mapsItemsOutsideTheTransaction() {
        List<OrderItemResponse> items = OrderItemResponse.fromAll(orderItemService.getOrderItemsByOrderId(orderId));

        assertThat(items).extracting(OrderItemResponse::productName).containsExactlyInAnyOrder("Keyboard", "Cable");
    }

    @Test
    void createdOrderResponse_includesItems() {
        Order created = orderService.createOrderWithItems(new Order(), List.of(new OrderItemRequest(keyboardId, 1).toEntity()));

        OrderResponse response = OrderResponse.from(created);
        assertThat(response.orderItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.productName()).isEqualTo("Keyboard");
                    assertThat(item.subtotal()).isEqualByComparingTo("25.00");
                });
    }
}
