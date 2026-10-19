package com.example.inventix.service;

import com.example.inventix.dto.StocktakeRequest;
import com.example.inventix.dto.StocktakeRequest.Line;
import com.example.inventix.dto.StocktakeResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.StocktakeServiceImpl;
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
 * Stocktakes against H2 with the real migrations: counted stock, ledger entries, and refusing stale count sheets.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, StocktakeServiceImpl.class})
class StocktakeIntegrationTest {

    @Autowired
    private StocktakeService stocktakeService;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private Long mouseId;    // 10 in stock
    private Long keyboardId; // 4 in stock
    private Long cableId;    // 7 in stock

    @BeforeEach
    void setUp() {
        mouseId = create("Mouse", "MS-1", 10);
        keyboardId = create("Keyboard", "KB-1", 4);
        cableId = create("Cable", "CB-1", 7);
    }

    private Long create(String name, String sku, int quantity) {
        return productService.createProduct(
                new Product(null, name, sku, null, new BigDecimal("5.00"), quantity, null, null)).getId();
    }

    private int stock(Long productId) {
        return productService.getProductById(productId).getQuantity();
    }

    private List<StockMovement> adjustments(Long productId) {
        return stockMovementRepository.findAll().stream()
                .filter(m -> m.getProduct().getId().equals(productId) && m.getNote() != null
                        && m.getNote().startsWith("Stocktake"))
                .toList();
    }

    @Test
    void setsStockToTheCount_andLedgersOnlyTheDifferences() {
        StocktakeResponse result = stocktakeService.applyCount(new StocktakeRequest("October count", List.of(
                new Line(mouseId, 8, 10), new Line(keyboardId, 6, null), new Line(cableId, 7, 7))));

        assertThat(stock(mouseId)).isEqualTo(8);
        assertThat(stock(keyboardId)).isEqualTo(6);
        assertThat(stock(cableId)).isEqualTo(7);
        assertThat(result.counted()).isEqualTo(3);
        assertThat(result.changes()).extracting(StocktakeResponse.Change::productSku, StocktakeResponse.Change::before,
                        StocktakeResponse.Change::after, StocktakeResponse.Change::delta)
                .containsExactly(tuple("MS-1", 10, 8, -2),
                        tuple("KB-1", 4, 6, 2));

        assertThat(adjustments(mouseId)).singleElement().satisfies(m -> {
            assertThat(m.getDelta()).isEqualTo(-2);
            assertThat(m.getReason()).isEqualTo(MovementReason.ADJUSTMENT);
            assertThat(m.getNote()).isEqualTo("Stocktake: October count");
        });
        assertThat(adjustments(cableId)).isEmpty();
    }

    @Test
    void aStaleCountSheetChangesNothing() {
        productService.adjustStock(keyboardId, -1, MovementReason.ADJUSTMENT, "Broken", null); // 4 -> 3

        assertThatThrownBy(() -> stocktakeService.applyCount(new StocktakeRequest("Count", List.of(
                new Line(mouseId, 9, 10), new Line(keyboardId, 5, 4)))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Keyboard (was 4, now 3)");

        assertThat(stock(mouseId)).isEqualTo(10);
        assertThat(adjustments(mouseId)).isEmpty();
    }

    @Test
    void aProductCountedTwiceOrMissing_isRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> stocktakeService.applyCount(new StocktakeRequest("Count", List.of(
                new Line(mouseId, 9, null), new Line(mouseId, 8, null)))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> stocktakeService.applyCount(new StocktakeRequest("Count", List.of(
                new Line(mouseId, 9, null), new Line(999L, 1, null)))))
                .isInstanceOf(ProductNotFoundException.class);

        assertThat(stock(mouseId)).isEqualTo(10);
    }

    @Test
    void countingZero_emptiesTheShelf() {
        stocktakeService.applyCount(new StocktakeRequest("Count", List.of(new Line(cableId, 0, 7))));

        assertThat(stock(cableId)).isZero();
    }
}
