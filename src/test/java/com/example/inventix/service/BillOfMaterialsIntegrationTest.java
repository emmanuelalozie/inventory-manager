package com.example.inventix.service;

import com.example.inventix.dto.ComponentsRequest;
import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductComponent;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.ProductionRunStatus;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.BillOfMaterialsServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.ProductionRunServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@Import({ProductServiceImpl.class, ProductionRunServiceImpl.class, BillOfMaterialsServiceImpl.class})
class BillOfMaterialsIntegrationTest {

    @Autowired
    private BillOfMaterialsService bomService;

    @Autowired
    private ProductionRunService productionRunService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long tableId; // made, 0 in stock
    private Long legId;   // 10 in stock
    private Long topId;   // 3 in stock

    @BeforeEach
    void setUp() {
        tableId = productService.createProduct(product("Table", "TBL", 0, ProductSource.MAKE)).getId();
        legId = productService.createProduct(product("Leg", "LEG", 10, ProductSource.BUY)).getId();
        topId = productService.createProduct(product("Top", "TOP", 3, ProductSource.BUY)).getId();
        bomService.replaceComponents(tableId, List.of(line(legId, 4), line(topId, 1)));
        flushAndClear();
    }

    private static Product product(String name, String sku, int quantity, ProductSource source) {
        Product product = new Product(null, name, sku, null, BigDecimal.TEN, quantity, null, null);
        product.setSource(source);
        return product;
    }

    private static ComponentsRequest.Line line(Long componentId, int quantity) {
        return new ComponentsRequest.Line(componentId, quantity);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private int stock(Long id) {
        return productRepository.findById(id).orElseThrow().getQuantity();
    }

    @Test
    void componentsAreStoredAndListedByName() {
        assertThat(bomService.getComponents(tableId))
                .extracting(c -> c.getComponent().getSku(), ProductComponent::getQuantity)
                .containsExactly(tuple("LEG", 4), tuple("TOP", 1));

        bomService.replaceComponents(tableId, List.of(line(topId, 2)));
        flushAndClear();
        assertThat(bomService.getComponents(tableId)).extracting(ProductComponent::getQuantity).containsExactly(2);
    }

    @Test
    void rulesForComponents() {
        assertThatThrownBy(() -> bomService.replaceComponents(legId, List.of(line(topId, 1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("made in-house");
        assertThatThrownBy(() -> bomService.replaceComponents(tableId, List.of(line(tableId, 1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("itself");
        assertThatThrownBy(() -> bomService.replaceComponents(tableId, List.of(line(legId, 1), line(legId, 2))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("more than once");
        assertThatThrownBy(() -> bomService.replaceComponents(tableId, List.of(line(9999L, 1))))
                .isInstanceOf(BadRequestException.class);

        // A kit made from tables can't then become a component of the table.
        Long kitId = productService.createProduct(product("Kit", "KIT", 0, ProductSource.MAKE)).getId();
        bomService.replaceComponents(kitId, List.of(line(tableId, 1)));
        assertThatThrownBy(() -> bomService.replaceComponents(tableId, List.of(line(kitId, 1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("loop");
    }

    @Test
    void componentsCantBeDeleted_andAMadeProductWithComponentsCantSwitchToBuy() {
        assertThatThrownBy(() -> productService.deleteProduct(legId)).isInstanceOf(ProductInUseException.class);

        Product changes = product("Table", "TBL", 0, ProductSource.BUY);
        changes.setQuantity(null);
        assertThatThrownBy(() -> productService.updateProduct(tableId, changes))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("components");

        // Deleting the made product removes its bill of materials with it.
        productService.deleteProduct(tableId);
        flushAndClear();
        productService.deleteProduct(legId);
    }

    @Test
    void completingARun_usesUpComponents_andAddsTheProduct() {
        Long runId = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 2, null, null)).getId();

        productionRunService.changeStatus(runId, ProductionRunStatus.COMPLETED);
        flushAndClear();

        assertThat(stock(tableId)).isEqualTo(2);
        assertThat(stock(legId)).isEqualTo(2);
        assertThat(stock(topId)).isEqualTo(1);
        List<StockMovement> used = stockMovementRepository.search(legId, MovementReason.CONSUMPTION, PageRequest.of(0, 5));
        assertThat(used).singleElement().satisfies(m -> {
            assertThat(m.getDelta()).isEqualTo(-8);
            assertThat(m.getProductionRunId()).isEqualTo(runId);
            assertThat(m.getNote()).isEqualTo("Used in production run #" + runId);
        });
        assertThat(stockMovementRepository.sumDeltaByProductId(legId)).isEqualTo(2);
    }

    @Test
    void aShortComponentStopsTheWholeRun() {
        Long runId = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 4, null, null)).getId();

        // 4 tables need 16 legs (10 in stock) and 4 tops (3 in stock).
        assertThatThrownBy(() -> productionRunService.changeStatus(runId, ProductionRunStatus.COMPLETED))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("Leg");
    }
}
