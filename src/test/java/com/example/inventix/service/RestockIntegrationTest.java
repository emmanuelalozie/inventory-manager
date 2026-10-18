package com.example.inventix.service;

import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import com.example.inventix.model.PurchaseOrderLine;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.model.Supplier;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.ProductionRunServiceImpl;
import com.example.inventix.service.impl.PurchaseOrderServiceImpl;
import com.example.inventix.service.impl.RestockServiceImpl;
import com.example.inventix.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@Import({ProductServiceImpl.class, SupplierServiceImpl.class, PurchaseOrderServiceImpl.class,
        ProductionRunServiceImpl.class, RestockServiceImpl.class})
class RestockIntegrationTest {

    @Autowired
    private RestockService restockService;

    @Autowired
    private ProductService productService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private ProductionRunService productionRunService;

    @Autowired
    private TestEntityManager entityManager;

    private Long acmeId;

    @BeforeEach
    void setUp() {
        Supplier acme = new Supplier();
        acme.setName("Acme");
        acmeId = supplierService.createSupplier(acme).getId();
    }

    private Long product(String sku, int quantity, ProductSource source, Long supplierId, Integer point, Integer reorderQty) {
        Product product = new Product(null, sku, sku, null, BigDecimal.ONE, quantity, null, null);
        product.setSource(source);
        product.setSupplierId(supplierId);
        product.setReorderPoint(point);
        product.setReorderQuantity(reorderQty);
        return productService.createProduct(product).getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void reorderList_showsOnlyProductsAtOrBelowTheirPoint_withWhatIsOnOrderAndWhatIsMissing() {
        Long bolt = product("BOLT", 4, ProductSource.BUY, acmeId, 5, 50);
        product("NUT", 5, ProductSource.BUY, null, 5, 20);      // no supplier
        product("GEAR", 0, ProductSource.MAKE, null, 2, null); // no reorder quantity
        product("WASHER", 6, ProductSource.BUY, acmeId, 5, 10);            // above its point
        product("PIN", 0, ProductSource.BUY, acmeId, null, 10);            // no reorder point
        purchaseOrderService.createPurchaseOrder(new PurchaseOrderRequest(acmeId, null,
                List.of(new PurchaseOrderRequest.Line(bolt, 30, null)), null));
        flushAndClear();

        List<RestockService.ReorderItem> list = restockService.getReorderList();

        assertThat(list).extracting(RestockService.ReorderItem::sku).containsExactly("GEAR", "BOLT", "NUT");
        RestockService.ReorderItem boltItem = list.get(1);
        assertThat(boltItem.onOrder()).isEqualTo(30);
        assertThat(boltItem.covered()).isTrue();
        assertThat(boltItem.supplierName()).isEqualTo("Acme");
        assertThat(list.get(2).problem()).isEqualTo("no supplier set");
        assertThat(list.get(0).problem()).isEqualTo("no reorder quantity set");
    }

    @Test
    void restockingABoughtProduct_addsItToTheSuppliersDraft_once() {
        Long bolt = product("BOLT", 1, ProductSource.BUY, acmeId, 5, 50);
        Long nut = product("NUT", 0, ProductSource.BUY, acmeId, 5, 20);

        RestockService.RestockResult first = restockService.restock(bolt);
        RestockService.RestockResult second = restockService.restock(nut);
        RestockService.RestockResult again = restockService.restock(bolt);
        flushAndClear();

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.purchaseOrderId()).isEqualTo(first.purchaseOrderId());
        assertThat(again.added()).isFalse();
        assertThat(purchaseOrderService.getPurchaseOrder(first.purchaseOrderId()).getLines())
                .extracting(PurchaseOrderLine::getQuantityOrdered).containsExactly(50, 20);
        // Nothing is ordered until someone reviews the draft.
        assertThat(purchaseOrderService.getPurchaseOrder(first.purchaseOrderId()).getStatus())
                .isEqualTo(PurchaseOrderStatus.DRAFT);
    }

    @Test
    void restockingAMadeProduct_plansOneProductionRun() {
        Long table = product("TABLE", 0, ProductSource.MAKE, null, 1, 4);

        RestockService.RestockResult first = restockService.restock(table);
        RestockService.RestockResult again = restockService.restock(table);

        assertThat(first.action()).isEqualTo("PRODUCTION_RUN");
        assertThat(first.created()).isTrue();
        assertThat(again.productionRunId()).isEqualTo(first.productionRunId());
        assertThat(productionRunService.getProductionRun(first.productionRunId()).getQuantity()).isEqualTo(4);
    }

    @Test
    void restockRefusesProductsThatAreNotSetUp() {
        Long nut = product("NUT", 0, ProductSource.BUY, null, 5, 20);
        Long gear = product("GEAR", 0, ProductSource.MAKE, null, 2, null);

        assertThatThrownBy(() -> restockService.restock(nut)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("no supplier set");
        assertThatThrownBy(() -> restockService.restock(gear)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("no reorder quantity set");
    }

    @Test
    void restockAll_actsOnReadyProducts_andSkipsCoveredOrIncompleteOnes() {
        product("BOLT", 0, ProductSource.BUY, acmeId, 5, 50);
        product("TABLE", 0, ProductSource.MAKE, null, 1, 4);
        Long chair = product("CHAIR", 0, ProductSource.MAKE, null, 2, 6);
        product("NUT", 0, ProductSource.BUY, null, 5, 20);
        productionRunService.createProductionRun(new ProductionRunRequest(chair, 10, null, null)); // covers CHAIR
        flushAndClear();

        List<RestockService.RestockResult> results = restockService.restockAll();

        assertThat(results).extracting(RestockService.RestockResult::sku, RestockService.RestockResult::action)
                .containsExactlyInAnyOrder(
                        tuple("BOLT", "PURCHASE_ORDER"),
                        tuple("TABLE", "PRODUCTION_RUN"),
                        tuple("CHAIR", "SKIPPED"),
                        tuple("NUT", "SKIPPED"));
        flushAndClear();

        // Run again: everything ready is now covered, so nothing new is added.
        assertThat(restockService.restockAll()).extracting(RestockService.RestockResult::action)
                .containsOnly("SKIPPED");
    }
}
