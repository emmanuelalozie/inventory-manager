package com.example.inventix.repository;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import jakarta.persistence.PersistenceException;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the schema built by the Flyway migrations (V2__stock_movements.sql).
 */
@DataJpaTest
class StockMovementRepositoryTest {

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Product mouse;
    private Product cable;

    @BeforeEach
    void setUp() {
        mouse = productRepository.save(product("Mouse", "SM-1"));
        cable = productRepository.save(product("Cable", "SM-2"));
    }

    private static Product product(String name, String sku) {
        Product product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setPrice(new BigDecimal("1.00"));
        product.setQuantity(0);
        return product;
    }

    private StockMovement save(Product product, int delta, MovementReason reason, String note, Long orderId) {
        return stockMovementRepository.save(new StockMovement(product, delta, reason, note, orderId));
    }

    @Test
    void save_setsIdAndTimestamp_andKeepsOptionalReferences() {
        StockMovement movement = new StockMovement(mouse, -2, MovementReason.SALE, null, 42L);
        movement.setPurchaseOrderId(7L);
        movement.setLocationId(3L);

        StockMovement saved = stockMovementRepository.save(movement);
        entityManager.flush();
        entityManager.clear();

        StockMovement loaded = stockMovementRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getDelta()).isEqualTo(-2);
        assertThat(loaded.getReason()).isEqualTo(MovementReason.SALE);
        assertThat(loaded.getNote()).isNull();
        assertThat(loaded.getOrderId()).isEqualTo(42L);
        assertThat(loaded.getPurchaseOrderId()).isEqualTo(7L);
        assertThat(loaded.getLocationId()).isEqualTo(3L);
        assertThat(loaded.getProduct().getId()).isEqualTo(mouse.getId());
    }

    @Test
    void orderId_hasNoForeignKey_soMovementsOutliveDeletedOrders() {
        save(mouse, 1, MovementReason.CANCEL, "Removed from order #999", 999L);

        entityManager.flush(); // order 999 doesn't exist; this must not fail

        assertThat(stockMovementRepository.findByOrderId(999L)).hasSize(1);
    }

    @Test
    void product_isRequiredByTheDatabase() {
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO stock_movements (product_id, delta, reason, created_at)"
                        + " VALUES (NULL, 1, 'ADJUSTMENT', LOCALTIMESTAMP)")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void zeroDelta_isRejectedByTheDatabase() {
        // IDENTITY ids: the insert runs on save, so the check constraint fails right there.
        assertThatThrownBy(() -> {
            save(mouse, 0, MovementReason.ADJUSTMENT, "nothing", null);
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);
    }

    @Test
    void search_returnsNewestFirst_withProductLoaded() {
        StockMovement first = save(mouse, 10, MovementReason.ADJUSTMENT, "Initial stock", null);
        StockMovement second = save(cable, 5, MovementReason.ADJUSTMENT, "Initial stock", null);
        StockMovement third = save(mouse, -1, MovementReason.SALE, null, 1L);
        entityManager.flush();
        entityManager.clear();

        List<StockMovement> results = stockMovementRepository.search(null, null, PageRequest.of(0, 10));

        assertThat(results).extracting(StockMovement::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(results).allSatisfy(movement -> assertThat(Hibernate.isInitialized(movement.getProduct())).isTrue());
    }

    @Test
    void search_filtersByProductAndReason() {
        save(mouse, 10, MovementReason.ADJUSTMENT, "Initial stock", null);
        save(mouse, -3, MovementReason.SALE, null, 1L);
        save(cable, -1, MovementReason.SALE, null, 1L);

        assertThat(stockMovementRepository.search(mouse.getId(), null, PageRequest.of(0, 10))).hasSize(2);
        assertThat(stockMovementRepository.search(null, MovementReason.SALE, PageRequest.of(0, 10))).hasSize(2);
        assertThat(stockMovementRepository.search(mouse.getId(), MovementReason.SALE, PageRequest.of(0, 10)))
                .extracting(StockMovement::getDelta)
                .containsExactly(-3);
        assertThat(stockMovementRepository.search(cable.getId(), MovementReason.ADJUSTMENT, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void search_pagesThroughResults() {
        for (int i = 1; i <= 5; i++) {
            save(mouse, i, MovementReason.ADJUSTMENT, "Recount " + i, null);
        }

        List<StockMovement> firstPage = stockMovementRepository.search(null, null, PageRequest.of(0, 2));
        List<StockMovement> lastPage = stockMovementRepository.search(null, null, PageRequest.of(2, 2));

        assertThat(firstPage).extracting(StockMovement::getDelta).containsExactly(5, 4);
        assertThat(lastPage).extracting(StockMovement::getDelta).containsExactly(1);
    }

    @Test
    void sumDeltaByProductId_addsUpMovements() {
        save(mouse, 10, MovementReason.ADJUSTMENT, "Initial stock", null);
        save(mouse, -4, MovementReason.SALE, null, 1L);
        save(mouse, 4, MovementReason.CANCEL, null, 1L);
        save(mouse, -2, MovementReason.SALE, null, 2L);

        assertThat(stockMovementRepository.sumDeltaByProductId(mouse.getId())).isEqualTo(8);
        assertThat(stockMovementRepository.sumDeltaByProductId(cable.getId())).isZero();
    }

    @Test
    void deleteByProductId_removesOnlyThatProductsMovements() {
        save(mouse, 10, MovementReason.ADJUSTMENT, "Initial stock", null);
        save(cable, 5, MovementReason.ADJUSTMENT, "Initial stock", null);

        stockMovementRepository.deleteByProductId(mouse.getId());
        entityManager.clear();

        assertThat(stockMovementRepository.findAll())
                .extracting(movement -> movement.getProduct().getId())
                .containsExactly(cable.getId());
    }
}
