package com.example.inventix.repository;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saves and loads orders against H2. "order" is a reserved word, so this proves the "orders"
 * table mapping, the item cascade and the STRING enum column all work.
 */
@DataJpaTest
class OrderRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductRepository productRepository;

    private Product widget;

    @BeforeEach
    void setUp() {
        Product product = new Product();
        product.setName("Widget");
        product.setSku("W-1");
        product.setDescription("A widget");
        product.setPrice(new BigDecimal("10.00"));
        product.setQuantity(50);
        widget = productRepository.save(product);
    }

    private OrderItem item(Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setPricePerUnit(product.getPrice());
        item.recalculateSubtotal();
        return item;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void savesAndLoadsOrderWithItems() {
        Order order = new Order();
        order.addItem(item(widget, 3));
        order.addItem(item(widget, 2));

        Order saved = orderRepository.save(order); // items are saved through the cascade
        flushAndClear();

        Order loaded = orderRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo("50.00");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getOrderItems()).hasSize(2);
        assertThat(loaded.getOrderItems()).allSatisfy(loadedItem -> {
            assertThat(loadedItem.getId()).isNotNull();
            assertThat(loadedItem.getOrderId()).isEqualTo(saved.getId());
            assertThat(loadedItem.getProduct().getId()).isEqualTo(widget.getId());
            assertThat(loadedItem.getPricePerUnit()).isEqualByComparingTo("10.00");
        });
        assertThat(loaded.getOrderItems())
                .extracting(OrderItem::getQuantity)
                .containsExactlyInAnyOrder(3, 2);
        assertThat(orderItemRepository.count()).isEqualTo(2);
    }

    @Test
    void storesStatusAsTextInOrdersTable() {
        Order order = new Order();
        order.setStatus(OrderStatus.SHIPPED);
        Order saved = orderRepository.save(order);
        flushAndClear();

        Object status = entityManager.getEntityManager()
                .createNativeQuery("SELECT CAST(status AS VARCHAR(20)) FROM orders WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(String.valueOf(status)).isEqualTo("SHIPPED");
    }

    @Test
    void findByStatus_returnsOnlyMatchingOrders() {
        Order pending = new Order();
        Order shipped = new Order();
        shipped.setStatus(OrderStatus.SHIPPED);
        orderRepository.save(pending);
        orderRepository.save(shipped);
        flushAndClear();

        assertThat(orderRepository.findByStatus(OrderStatus.SHIPPED))
                .extracting(Order::getId)
                .containsExactly(shipped.getId());
        assertThat(orderRepository.findByStatus(OrderStatus.DELIVERED)).isEmpty();
    }

    @Test
    void removingItemFromOrder_deletesIt() {
        Order order = new Order();
        order.addItem(item(widget, 3));
        Order saved = orderRepository.save(order);
        flushAndClear();

        Order loaded = orderRepository.findById(saved.getId()).orElseThrow();
        loaded.removeItem(loaded.getOrderItems().get(0));
        flushAndClear();

        assertThat(orderItemRepository.count()).isZero();
        assertThat(orderRepository.findById(saved.getId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void deletingOrder_deletesItsItems() {
        Order order = new Order();
        order.addItem(item(widget, 1));
        Order saved = orderRepository.save(order);
        flushAndClear();

        orderRepository.delete(orderRepository.findById(saved.getId()).orElseThrow());
        flushAndClear();

        assertThat(orderRepository.existsById(saved.getId())).isFalse();
        assertThat(orderItemRepository.count()).isZero();
    }

    @Test
    void existsByProductId_detectsProductsUsedByOrders() {
        Product unused = new Product();
        unused.setName("Unused");
        unused.setSku("U-1");
        unused.setPrice(new BigDecimal("1.00"));
        unused.setQuantity(1);
        unused = productRepository.save(unused);

        Order order = new Order();
        order.addItem(item(widget, 1));
        orderRepository.save(order);
        flushAndClear();

        assertThat(orderItemRepository.existsByProductId(widget.getId())).isTrue();
        assertThat(orderItemRepository.existsByProductId(unused.getId())).isFalse();
    }
}
