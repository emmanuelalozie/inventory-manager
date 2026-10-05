package com.example.inventix.repository;

import com.example.inventix.model.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks the @Version mapping on Product against the real database (O-8).
 * <p>
 * Runs without a test transaction so every repository call commits on its own and the loaded
 * products are detached copies, like two requests reading the same product at the same time.
 * The product is deleted afterwards because the data is committed.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductVersionRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    private Long productId;

    @AfterEach
    void cleanUp() {
        if (productId != null) {
            productRepository.deleteById(productId);
        }
    }

    @Test
    void versionStartsAtZeroAndIncrementsOnUpdate() {
        Product saved = productRepository.save(
                new Product(null, "Version Test", "VER-001", null, new BigDecimal("5.00"), 10, null, null));
        productId = saved.getId();
        assertThat(saved.getVersion()).isEqualTo(0L);

        Product loaded = productRepository.findById(productId).orElseThrow();
        loaded.setQuantity(9);
        Product updated = productRepository.save(loaded);

        assertThat(updated.getVersion()).isEqualTo(1L);
    }

    @Test
    void savingAStaleCopyFailsInsteadOfOverwriting() {
        Product saved = productRepository.save(
                new Product(null, "Version Test", "VER-002", null, new BigDecimal("5.00"), 10, null, null));
        productId = saved.getId();

        Product first = productRepository.findById(productId).orElseThrow();
        Product second = productRepository.findById(productId).orElseThrow();

        first.setQuantity(8); // e.g. one order takes 2 units
        productRepository.save(first);

        second.setQuantity(7); // a concurrent order computed its stock from the old value
        assertThatThrownBy(() -> productRepository.save(second))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(8);
    }
}
