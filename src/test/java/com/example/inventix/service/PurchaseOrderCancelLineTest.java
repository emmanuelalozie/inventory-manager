package com.example.inventix.service;

import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.model.Product;
import com.example.inventix.model.PurchaseOrder;
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

@DataJpaTest
@Import({ProductServiceImpl.class, SupplierServiceImpl.class, PurchaseOrderServiceImpl.class,
        ProductionRunServiceImpl.class, RestockServiceImpl.class})
class PurchaseOrderCancelLineTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private ProductService productService;

    @Autowired
    private RestockService restockService;

    @Autowired
    private TestEntityManager entityManager;

    private Long acmeId;
    private Long boltId;
    private Long nutId;

    @BeforeEach
    void setUp() {
        Supplier acme = new Supplier();
        acme.setName("Acme");
        acmeId = supplierService.createSupplier(acme).getId();
        Product bolt = new Product(null, "Bolt", "BOLT", null, BigDecimal.ONE, 0, null, null);
        bolt.setSupplierId(acmeId);
        bolt.setReorderPoint(50);
        bolt.setReorderQuantity(100);
        boltId = productService.createProduct(bolt).getId();
        nutId = productService.createProduct(new Product(null, "Nut", "NUT", null, BigDecimal.ONE, 0, null, null)).getId();
    }

    private Long ordered() {
        Long id = purchaseOrderService.createPurchaseOrder(new PurchaseOrderRequest(acmeId, null, List.of(
                new PurchaseOrderRequest.Line(boltId, 100, new BigDecimal("0.20")),
                new PurchaseOrderRequest.Line(nutId, 40, null)), null)).getId();
        purchaseOrderService.changeStatus(id, PurchaseOrderStatus.ORDERED);
        return id;
    }

    private Long lineOf(Long poId, Long productId) {
        return purchaseOrderService.getPurchaseOrder(poId).getLines().stream()
                .filter(l -> l.getProduct().getId().equals(productId)).findFirst().orElseThrow().getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void cancellingTheRestOfOneLine_keepsTheOtherLineOpen_andTheOrderClosesWhenSettled() {
        Long poId = ordered();
        Long boltLine = lineOf(poId, boltId);
        Long nutLine = lineOf(poId, nutId);
        purchaseOrderService.receive(poId, new ReceiptRequest(List.of(new ReceiptRequest.Line(boltLine, 60)), null));

        PurchaseOrder afterCancel = purchaseOrderService.cancelRemaining(poId, boltLine);
        PurchaseOrderLine bolt = afterCancel.getLines().stream().filter(l -> l.getId().equals(boltLine)).findFirst().orElseThrow();
        assertThat(bolt.getQuantityCancelled()).isEqualTo(40);
        assertThat(bolt.getQuantityRemaining()).isZero();
        assertThat(afterCancel.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(afterCancel.getTotalCost()).isEqualByComparingTo("12.00"); // 60 x 0.20, the cancelled 40 left out

        assertThatThrownBy(() -> purchaseOrderService.receive(poId,
                new ReceiptRequest(List.of(new ReceiptRequest.Line(boltLine, 1)), null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> purchaseOrderService.cancelRemaining(poId, boltLine)).isInstanceOf(ConflictException.class);

        purchaseOrderService.receive(poId, new ReceiptRequest(List.of(new ReceiptRequest.Line(nutLine, 40)), null));
        flushAndClear();
        // Everything that will come has come, but part was cancelled: CLOSED, not RECEIVED.
        assertThat(purchaseOrderService.getPurchaseOrder(poId).getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
    }

    @Test
    void cancellingEveryLineBeforeAnythingArrives_cancelsTheOrder() {
        Long poId = ordered();
        purchaseOrderService.cancelRemaining(poId, lineOf(poId, boltId));
        PurchaseOrder result = purchaseOrderService.cancelRemaining(poId, lineOf(poId, nutId));

        assertThat(result.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
    }

    @Test
    void cancelledQuantitiesAreNotCountedAsOnOrder() {
        Long poId = ordered();
        assertThat(restockService.getReorderList()).singleElement().satisfies(i -> assertThat(i.onOrder()).isEqualTo(100));

        purchaseOrderService.cancelRemaining(poId, lineOf(poId, boltId));
        flushAndClear();

        assertThat(restockService.getReorderList()).singleElement().satisfies(i -> assertThat(i.onOrder()).isZero());
    }

    @Test
    void draftsCantCancelLines() {
        Long draftId = purchaseOrderService.createPurchaseOrder(new PurchaseOrderRequest(acmeId, null,
                List.of(new PurchaseOrderRequest.Line(boltId, 5, null)), null)).getId();

        assertThatThrownBy(() -> purchaseOrderService.cancelRemaining(draftId, lineOf(draftId, boltId)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("edit the draft");
    }
}
