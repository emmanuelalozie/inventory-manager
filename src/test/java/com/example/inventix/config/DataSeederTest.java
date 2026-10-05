package com.example.inventix.config;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is a file now, so DataSeeder runs on every start. It must only seed the first time,
 * and the seeded stock must be in the stock ledger.
 */
@DataJpaTest
@Import(ProductServiceImpl.class)
class DataSeederTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private DataSeeder seeder() {
        return new DataSeeder(productRepository, productService);
    }

    @Test
    void run_onEmptyDatabase_seedsSampleProducts() {
        seeder().run();

        assertThat(productRepository.count()).isEqualTo(5);
        assertThat(productRepository.existsBySku("KB-002")).isTrue();
    }

    @Test
    void run_recordsSeededStockAsInitialAdjustments() {
        seeder().run();

        List<StockMovement> movements = stockMovementRepository.findAll();
        // Laptop Stand starts at 0, so it has no movement.
        assertThat(movements).hasSize(4);
        assertThat(movements).allSatisfy(movement -> {
            assertThat(movement.getReason()).isEqualTo(MovementReason.ADJUSTMENT);
            assertThat(movement.getNote()).isEqualTo("Initial stock");
            assertThat(movement.getCreatedAt()).isNotNull();
        });
        for (Product product : productRepository.findAll()) {
            assertThat(stockMovementRepository.sumDeltaByProductId(product.getId()))
                    .as("ledger sum for %s", product.getSku())
                    .isEqualTo(product.getQuantity().longValue());
        }
    }

    @Test
    void run_secondStart_doesNotSeedAgain() {
        DataSeeder seeder = seeder();
        seeder.run();
        seeder.run();

        assertThat(productRepository.count()).isEqualTo(5);
        assertThat(stockMovementRepository.count()).isEqualTo(4);
    }

    @Test
    void run_whenUserAlreadyHasProducts_leavesThemAlone() {
        Product own = new Product();
        own.setName("My product");
        own.setSku("MINE-1");
        own.setPrice(new BigDecimal("1.00"));
        own.setQuantity(3);
        productRepository.save(own);

        seeder().run();

        assertThat(productRepository.count()).isEqualTo(1);
        assertThat(productRepository.existsBySku("WM-001")).isFalse();
    }
}
