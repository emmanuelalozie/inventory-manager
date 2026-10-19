package com.example.inventix.service;

import com.example.inventix.dto.OrderReturnRequest;
import com.example.inventix.dto.OrderReturnRequest.Line;
import com.example.inventix.dto.OrderReturnsResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.CustomerServiceImpl;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.OrderReturnServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Customer returns against H2 with the real migrations: stock comes back with a RETURN movement, and an order can't
 * take back more than it sold.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, OrderItemServiceImpl.class, OrderServiceImpl.class, OrderReturnServiceImpl.class,
        CustomerServiceImpl.class})
class OrderReturnIntegrationTest {

    @Autowired
    private OrderReturnService orderReturnService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private Long mouseId;    // 10 in stock
    private Long keyboardId; // 5 in stock
    private Long orderId;    // 3 mice (on two lines) and 1 keyboard, shipped

    @BeforeEach
    void setUp() {
        mouseId = create("Mouse", "MS-1", 10);
        keyboardId = create("Keyboard", "KB-1", 5);
        orderId = orderService.createOrderWithItems(new Order(),
                List.of(item(mouseId, 2), item(keyboardId, 1), item(mouseId, 1))).getId();
        orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED);
    }

    private Long create(String name, String sku, int quantity) {
        return productService.createProduct(
                new Product(null, name, sku, null, new BigDecimal("5.00"), quantity, null, null)).getId();
    }

    private static OrderItem item(Long productId, int quantity) {
        Product product = new Product();
        product.setId(productId);
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }

    private int stock(Long productId) {
        return productService.getProductById(productId).getQuantity();
    }

    @Test
    void listsWhatWasSoldPerProduct_beforeAnyReturn() {
        assertThat(orderReturnService.getReturns(orderId).lines())
                .extracting(OrderReturnsResponse.Line::productSku, OrderReturnsResponse.Line::quantitySold,
                        OrderReturnsResponse.Line::quantityReturned, OrderReturnsResponse.Line::quantityReturnable)
                .containsExactly(tuple("MS-1", 3, 0, 3), tuple("KB-1", 1, 0, 1));
    }

    @Test
    void aReturnPutsStockBack_withAReturnMovementOnTheOrder() {
        OrderReturnsResponse result = orderReturnService.returnItems(orderId,
                new OrderReturnRequest(List.of(new Line(mouseId, 2)), " Wrong colour "));

        assertThat(stock(mouseId)).isEqualTo(9); // 10 - 3 sold + 2 returned
        assertThat(result.lines().get(0).quantityReturned()).isEqualTo(2);
        assertThat(result.lines().get(0).quantityReturnable()).isEqualTo(1);

        List<StockMovement> returns = stockMovementRepository.findByOrderId(orderId).stream()
                .filter(m -> m.getReason() == MovementReason.RETURN).toList();
        assertThat(returns).singleElement().satisfies(m -> {
            assertThat(m.getDelta()).isEqualTo(2);
            assertThat(m.getNote()).isEqualTo("Returned from order #" + orderId + ": Wrong colour");
        });
        assertThat(orderService.getOrderById(orderId).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void returnsAddUp_andCantGoPastWhatWasSold() {
        orderReturnService.returnItems(orderId, new OrderReturnRequest(List.of(new Line(mouseId, 2)), null));

        assertThatThrownBy(() -> orderReturnService.returnItems(orderId,
                new OrderReturnRequest(List.of(new Line(keyboardId, 1), new Line(mouseId, 2)), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only 1 of 'Mouse'");

        // The keyboard line came first but nothing was written.
        assertThat(stock(keyboardId)).isEqualTo(4);
        orderReturnService.returnItems(orderId, new OrderReturnRequest(List.of(new Line(mouseId, 1)), null));
        assertThat(stock(mouseId)).isEqualTo(10);
    }

    @Test
    void aProductNotOnTheOrderOrListedTwice_isRefused() {
        Long cableId = create("Cable", "CB-1", 3);

        assertThatThrownBy(() -> orderReturnService.returnItems(orderId,
                new OrderReturnRequest(List.of(new Line(cableId, 1)), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("isn't on order");
        assertThatThrownBy(() -> orderReturnService.returnItems(orderId,
                new OrderReturnRequest(List.of(new Line(mouseId, 1), new Line(mouseId, 1)), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    void onlyShippedOrDeliveredOrdersTakeReturns() {
        Long pending = orderService.createOrderWithItems(new Order(), List.of(item(mouseId, 1))).getId();

        assertThatThrownBy(() -> orderReturnService.returnItems(pending,
                new OrderReturnRequest(List.of(new Line(mouseId, 1)), null)))
                .isInstanceOf(InvalidOrderStateException.class);

        orderService.updateOrderStatus(orderId, OrderStatus.DELIVERED);
        orderReturnService.returnItems(orderId, new OrderReturnRequest(List.of(new Line(keyboardId, 1)), null));
        assertThat(stock(keyboardId)).isEqualTo(5);
    }
}
