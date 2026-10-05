package com.example.inventix.service;

import com.example.inventix.dto.OrderItemRequest;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.InvalidOrderStateException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Runs the real services against H2 to check that stock is reserved and given back,
 * and that order totals are right after the changes are written to the database.
 * Each test runs in one transaction that is rolled back afterwards.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, OrderItemServiceImpl.class, OrderServiceImpl.class})
class OrderStockIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long keyboardId; // 25.00 each, 10 in stock
    private Long cableId;    // 4.50 each, 5 in stock

    @Autowired
    private StockMovementRepository stockMovementRepository;

    // Created through the service, so each product's starting stock is in the ledger too.
    @BeforeEach
    void setUp() {
        keyboardId = productService.createProduct(product("Keyboard", "KB-1", "25.00", 10)).getId();
        cableId = productService.createProduct(product("Cable", "CB-1", "4.50", 5)).getId();
    }

    // The ledger is complete: each product's movements add up to its stored quantity.
    private void assertLedgerMatchesStock() {
        flushAndClear();
        for (Long productId : List.of(keyboardId, cableId)) {
            assertThat(stockMovementRepository.sumDeltaByProductId(productId))
                    .as("sum of movements for product %d", productId)
                    .isEqualTo(stockOf(productId));
        }
    }

    // Movements caused by an order, as (product, delta, reason) tuples.
    private List<Tuple> orderMovements(Long orderId) {
        return stockMovementRepository.findByOrderId(orderId).stream()
                .map(movement -> tuple(movement.getProduct().getId(), movement.getDelta(), movement.getReason()))
                .toList();
    }

    private static Product product(String name, String sku, String price, int quantity) {
        Product product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setPrice(new BigDecimal(price));
        product.setQuantity(quantity);
        return product;
    }

    // An item as the controller builds it from the request body.
    private static OrderItem item(Long productId, int quantity) {
        return new OrderItemRequest(productId, quantity).toEntity();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getQuantity();
    }

    private Order reload(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    // Creates a pending order (3 keyboards + 2 cables, total 84.00) and starts a fresh persistence
    // context, like a separate HTTP request would.
    private Long createOrderWithKeyboardsAndCables() {
        Long orderId = orderService.createOrderWithItems(new Order(), List.of(item(keyboardId, 3), item(cableId, 2))).getId();
        flushAndClear();
        return orderId;
    }

    @Test
    void createOrderWithItems_reservesStockAndComputesTotal() {
        Order created = orderService.createOrderWithItems(new Order(), List.of(item(keyboardId, 3), item(cableId, 2)));

        assertThat(created.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(created.getTotalAmount()).isEqualByComparingTo("84.00"); // 3 x 25.00 + 2 x 4.50

        flushAndClear();
        Order reloaded = reload(created.getId());
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("84.00");
        assertThat(reloaded.getOrderItems()).hasSize(2);
        assertThat(reloaded.getOrderItems())
                .extracting(OrderItem::getSubtotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("75.00"), new BigDecimal("9.00"));
        assertThat(stockOf(keyboardId)).isEqualTo(7);
        assertThat(stockOf(cableId)).isEqualTo(3);
    }

    @Test
    void createOrder_withoutItems_isPendingWithZeroTotal() {
        Long orderId = orderService.createOrder(new Order()).getId();
        flushAndClear();

        Order reloaded = reload(orderId);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("0");
        assertThat(reloaded.getOrderItems()).isEmpty();
    }

    @Test
    void addingItem_withTooLittleStock_throwsAndChangesNothing() {
        Long orderId = orderService.createOrder(new Order()).getId();

        assertThatThrownBy(() -> orderItemService.createOrderItem(orderId, item(cableId, 6)))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stockOf(cableId)).isEqualTo(5);
        Order order = reload(orderId);
        assertThat(order.getOrderItems()).isEmpty();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void removingItem_restoresStockAndRecalculatesTotal() {
        Long orderId = createOrderWithKeyboardsAndCables();
        Long keyboardItemId = reload(orderId).getOrderItems().stream()
                .filter(orderItem -> orderItem.getProduct().getId().equals(keyboardId))
                .findFirst().orElseThrow()
                .getId();

        orderItemService.deleteOrderItem(keyboardItemId);
        flushAndClear();

        assertThat(orderItemRepository.existsById(keyboardItemId)).isFalse();
        Order reloaded = reload(orderId);
        assertThat(reloaded.getOrderItems()).hasSize(1);
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("9.00");
        assertThat(stockOf(keyboardId)).isEqualTo(10);
        assertThat(stockOf(cableId)).isEqualTo(3);
    }

    @Test
    void removingItem_addedInTheSameTransaction_deletesIt() {
        Long orderId = orderService.createOrder(new Order()).getId();
        Long itemId = orderItemService.createOrderItem(orderId, item(keyboardId, 2)).getId();

        orderItemService.deleteOrderItem(itemId); // no flush in between
        flushAndClear();

        assertThat(orderItemRepository.existsById(itemId)).isFalse();
        assertThat(reload(orderId).getTotalAmount()).isEqualByComparingTo("0");
        assertThat(stockOf(keyboardId)).isEqualTo(10);
    }

    @Test
    void changingItemQuantity_adjustsStockAndTotal() {
        Long orderId = createOrderWithKeyboardsAndCables();
        Long keyboardItemId = reload(orderId).getOrderItems().stream()
                .filter(orderItem -> orderItem.getProduct().getId().equals(keyboardId))
                .findFirst().orElseThrow()
                .getId();
        OrderItem changes = new OrderItem();
        changes.setQuantity(5);

        orderItemService.updateOrderItem(keyboardItemId, changes);
        flushAndClear();

        assertThat(stockOf(keyboardId)).isEqualTo(5);
        assertThat(reload(orderId).getTotalAmount()).isEqualByComparingTo("134.00"); // 5 x 25.00 + 9.00
    }

    @Test
    void updateOrder_givesBackOldStockAndReservesNewStock() {
        Long orderId = createOrderWithKeyboardsAndCables();
        Order changes = new Order();
        changes.setOrderItems(List.of(item(keyboardId, 1), item(cableId, 4)));

        Order updated = orderService.updateOrder(orderId, changes);
        assertThat(updated.getTotalAmount()).isEqualByComparingTo("43.00"); // 25.00 + 4 x 4.50
        flushAndClear();

        assertThat(stockOf(keyboardId)).isEqualTo(9);
        assertThat(stockOf(cableId)).isEqualTo(1);
        assertThat(reload(orderId).getOrderItems()).hasSize(2);
        assertThat(orderItemRepository.count()).isEqualTo(2);
    }

    @Test
    void cancellingOrder_restoresStock_andDeletingItLaterDoesNotRestoreAgain() {
        Long orderId = createOrderWithKeyboardsAndCables();

        orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED);
        flushAndClear();

        assertThat(stockOf(keyboardId)).isEqualTo(10);
        assertThat(stockOf(cableId)).isEqualTo(5);
        Order cancelled = reload(orderId);
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getOrderItems()).hasSize(2); // kept as a record

        orderService.deleteOrder(orderId);
        flushAndClear();

        assertThat(orderRepository.existsById(orderId)).isFalse();
        assertThat(orderItemRepository.count()).isZero();
        assertThat(stockOf(keyboardId)).isEqualTo(10);
        assertThat(stockOf(cableId)).isEqualTo(5);
    }

    @Test
    void deletingPendingOrder_restoresStock() {
        Long orderId = createOrderWithKeyboardsAndCables();

        orderService.deleteOrder(orderId);
        flushAndClear();

        assertThat(orderRepository.existsById(orderId)).isFalse();
        assertThat(stockOf(keyboardId)).isEqualTo(10);
        assertThat(stockOf(cableId)).isEqualTo(5);
    }

    @Test
    void shippedOrder_keepsItsStockReserved_andCannotBeEdited() {
        Long orderId = createOrderWithKeyboardsAndCables();

        orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED);
        flushAndClear();
        assertThat(stockOf(keyboardId)).isEqualTo(7);

        assertThatThrownBy(() -> orderItemService.createOrderItem(orderId, item(cableId, 1)))
                .isInstanceOf(InvalidOrderStateException.class);
        assertThat(stockOf(cableId)).isEqualTo(3);
    }

    @Test
    void creatingProducts_recordsInitialStock() {
        flushAndClear();
        List<StockMovement> movements = stockMovementRepository.findAll();

        assertThat(movements)
                .extracting(movement -> movement.getProduct().getId(), StockMovement::getDelta,
                        StockMovement::getReason, StockMovement::getNote)
                .containsExactlyInAnyOrder(
                        tuple(keyboardId, 10, MovementReason.ADJUSTMENT, "Initial stock"),
                        tuple(cableId, 5, MovementReason.ADJUSTMENT, "Initial stock"));
        assertLedgerMatchesStock();
    }

    @Test
    void addingItems_recordsSaleMovementsForTheOrder() {
        Long orderId = createOrderWithKeyboardsAndCables();

        assertThat(orderMovements(orderId)).containsExactlyInAnyOrder(
                tuple(keyboardId, -3, MovementReason.SALE),
                tuple(cableId, -2, MovementReason.SALE));
        assertLedgerMatchesStock();
    }

    @Test
    void changingItemQuantity_recordsTheDifference() {
        Long orderId = createOrderWithKeyboardsAndCables();
        Long keyboardItemId = reload(orderId).getOrderItems().stream()
                .filter(orderItem -> orderItem.getProduct().getId().equals(keyboardId))
                .findFirst().orElseThrow()
                .getId();
        OrderItem more = new OrderItem();
        more.setQuantity(5);
        OrderItem fewer = new OrderItem();
        fewer.setQuantity(1);

        orderItemService.updateOrderItem(keyboardItemId, more);   // 3 -> 5: two more sold
        orderItemService.updateOrderItem(keyboardItemId, fewer);  // 5 -> 1: four given back

        assertThat(orderMovements(orderId)).containsExactlyInAnyOrder(
                tuple(keyboardId, -3, MovementReason.SALE),
                tuple(cableId, -2, MovementReason.SALE),
                tuple(keyboardId, -2, MovementReason.SALE),
                tuple(keyboardId, 4, MovementReason.CANCEL));
        assertLedgerMatchesStock();
        assertThat(stockOf(keyboardId)).isEqualTo(9);
    }

    @Test
    void removingItem_recordsCancelMovement() {
        Long orderId = createOrderWithKeyboardsAndCables();
        Long cableItemId = reload(orderId).getOrderItems().stream()
                .filter(orderItem -> orderItem.getProduct().getId().equals(cableId))
                .findFirst().orElseThrow()
                .getId();

        orderItemService.deleteOrderItem(cableItemId);

        assertThat(orderMovements(orderId)).contains(tuple(cableId, 2, MovementReason.CANCEL));
        assertLedgerMatchesStock();
    }

    @Test
    void cancellingOrder_recordsCancelMovementPerItem() {
        Long orderId = createOrderWithKeyboardsAndCables();

        orderService.updateOrderStatus(orderId, OrderStatus.CANCELLED);

        assertThat(orderMovements(orderId)).containsExactlyInAnyOrder(
                tuple(keyboardId, -3, MovementReason.SALE),
                tuple(cableId, -2, MovementReason.SALE),
                tuple(keyboardId, 3, MovementReason.CANCEL),
                tuple(cableId, 2, MovementReason.CANCEL));
        assertThat(stockMovementRepository.findByOrderId(orderId))
                .filteredOn(movement -> movement.getReason() == MovementReason.CANCEL)
                .extracting(StockMovement::getNote)
                .containsOnly("Order #" + orderId + " cancelled");
        assertLedgerMatchesStock();

        // Deleting the cancelled order gives nothing back, so it writes nothing.
        orderService.deleteOrder(orderId);
        assertThat(orderMovements(orderId)).hasSize(4);
        assertLedgerMatchesStock();
    }

    @Test
    void deletingPendingOrder_recordsCancelMovements_thatKeepTheOrderId() {
        Long orderId = createOrderWithKeyboardsAndCables();

        orderService.deleteOrder(orderId);
        flushAndClear();

        assertThat(orderRepository.existsById(orderId)).isFalse();
        assertThat(orderMovements(orderId)).containsExactlyInAnyOrder(
                tuple(keyboardId, -3, MovementReason.SALE),
                tuple(cableId, -2, MovementReason.SALE),
                tuple(keyboardId, 3, MovementReason.CANCEL),
                tuple(cableId, 2, MovementReason.CANCEL));
        assertLedgerMatchesStock();
    }

    @Test
    void failedStockChange_writesNoMovement() {
        Long orderId = orderService.createOrder(new Order()).getId();
        long before = stockMovementRepository.count();

        assertThatThrownBy(() -> orderItemService.createOrderItem(orderId, item(cableId, 6)))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stockMovementRepository.count()).isEqualTo(before);
        assertLedgerMatchesStock();
    }

    @Test
    void ledgerMatchesStock_afterAMixOfOrdersAndManualAdjustments() {
        Long first = createOrderWithKeyboardsAndCables();
        Long second = orderService.createOrderWithItems(new Order(), List.of(item(keyboardId, 2))).getId();
        productService.adjustStock(cableId, 20, MovementReason.ADJUSTMENT, "Delivery counted in", null);
        productService.adjustStock(keyboardId, -1, MovementReason.ADJUSTMENT, "Damaged", null);
        Order changes = new Order();
        changes.setOrderItems(List.of(item(cableId, 7)));
        orderService.updateOrder(first, changes);
        orderService.updateOrderStatus(second, OrderStatus.CANCELLED);
        Long third = orderService.createOrderWithItems(new Order(), List.of(item(keyboardId, 4))).getId();
        orderService.updateOrderStatus(third, OrderStatus.SHIPPED);
        flushAndClear();

        assertThat(stockOf(keyboardId)).isEqualTo(5);  // 10 - 1 damaged - 4 shipped
        assertThat(stockOf(cableId)).isEqualTo(18);    // 5 + 20 - 7
        assertLedgerMatchesStock();
    }

    @Test
    void deletingUnusedProduct_removesItsMovements() {
        productService.adjustStock(cableId, 3, MovementReason.ADJUSTMENT, "Recount", null);
        flushAndClear();

        productService.deleteProduct(cableId);
        flushAndClear();

        assertThat(productRepository.existsById(cableId)).isFalse();
        assertThat(stockMovementRepository.sumDeltaByProductId(cableId)).isZero();
        assertThat(stockMovementRepository.findAll())
                .extracting(movement -> movement.getProduct().getId())
                .containsOnly(keyboardId);
    }

    @Test
    void deletingProductUsedByAnOrder_isRejected() {
        createOrderWithKeyboardsAndCables();

        assertThatThrownBy(() -> productService.deleteProduct(keyboardId))
                .isInstanceOf(ProductInUseException.class);
        assertThat(productRepository.existsById(keyboardId)).isTrue();
    }
}
