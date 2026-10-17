package com.example.inventix.service;

import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.ProductionRunServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({ProductServiceImpl.class, ProductionRunServiceImpl.class})
class ProductionRunIntegrationTest {

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

    private Long tableId; // made in-house, 2 in stock

    @BeforeEach
    void setUp() {
        Product table = new Product(null, "Table", "TBL-1", null, new BigDecimal("120.00"), 2, null, null);
        table.setSource(ProductSource.MAKE);
        tableId = productService.createProduct(table).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private int stock() {
        return productRepository.findById(tableId).orElseThrow().getQuantity();
    }

    @Test
    void plannedRunDoesNotChangeStock_untilItIsCompleted() {
        Long runId = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 5, "Oak batch", null)).getId();
        flushAndClear();
        assertThat(stock()).isEqualTo(2);

        productionRunService.updateProductionRun(runId, new ProductionRunRequest(tableId, 6, "Oak batch", null));
        ProductionRun completed = productionRunService.changeStatus(runId, ProductionRunStatus.COMPLETED);
        flushAndClear();

        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(stock()).isEqualTo(8);
        List<StockMovement> made = stockMovementRepository.search(tableId, MovementReason.PRODUCTION, PageRequest.of(0, 10));
        assertThat(made).singleElement().satisfies(m -> {
            assertThat(m.getDelta()).isEqualTo(6);
            assertThat(m.getProductionRunId()).isEqualTo(runId);
            assertThat(m.getNote()).isEqualTo("Production run #" + runId + ": Oak batch");
        });
        assertThat(stockMovementRepository.sumDeltaByProductId(tableId)).isEqualTo(8);
    }

    @Test
    void completedAndCancelledRunsAreFinal() {
        Long done = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 1, null, null)).getId();
        productionRunService.changeStatus(done, ProductionRunStatus.COMPLETED);
        Long dropped = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 1, null, null)).getId();
        productionRunService.changeStatus(dropped, ProductionRunStatus.CANCELLED);

        assertThatThrownBy(() -> productionRunService.changeStatus(done, ProductionRunStatus.CANCELLED))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productionRunService.changeStatus(dropped, ProductionRunStatus.COMPLETED))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productionRunService.updateProductionRun(done, new ProductionRunRequest(tableId, 9, null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productionRunService.deleteProductionRun(done)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productService.deleteProduct(tableId)).isInstanceOf(ProductInUseException.class);

        productionRunService.deleteProductionRun(dropped);
        flushAndClear();
        assertThatThrownBy(() -> productionRunService.getProductionRun(dropped)).isInstanceOf(NotFoundException.class);
        assertThat(stock()).isEqualTo(3);
    }

    @Test
    void planIfNone_reusesThePlannedRun() {
        ProductionRunService.PlannedRun first = productionRunService.planIfNone(tableId, 10);
        ProductionRunService.PlannedRun second = productionRunService.planIfNone(tableId, 99);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.run().getId()).isEqualTo(first.run().getId());
        assertThat(second.run().getQuantity()).isEqualTo(10);
    }

    @Test
    void listFiltersByStatusAndProduct_newestFirst() {
        Long a = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 1, null, null)).getId();
        Long b = productionRunService.createProductionRun(new ProductionRunRequest(tableId, 2, null, null)).getId();
        productionRunService.changeStatus(a, ProductionRunStatus.CANCELLED);
        flushAndClear();

        assertThat(productionRunService.searchProductionRuns(null, tableId, null, null).getContent())
                .extracting(ProductionRun::getId).containsExactly(b, a);
        assertThat(productionRunService.searchProductionRuns(ProductionRunStatus.PLANNED, null, 0, 5).getContent())
                .extracting(ProductionRun::getId).containsExactly(b);
    }
}
