package com.example.inventix.service;

import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderLine;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.model.StockMovement;
import com.example.inventix.model.Supplier;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.PurchaseOrderServiceImpl;
import com.example.inventix.service.impl.SupplierServiceImpl;
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

/**
 * Purchase orders with the real services and migrations: drafts, status rules, partial receipts and the
 * RECEIPT entries they write in the stock ledger.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, SupplierServiceImpl.class, PurchaseOrderServiceImpl.class})
class PurchaseOrderIntegrationTest {

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long acmeId;
    private Long boltId; // 10 in stock
    private Long nutId;  // 0 in stock

    @BeforeEach
    void setUp() {
        Supplier acme = new Supplier();
        acme.setName("Acme");
        acmeId = supplierService.createSupplier(acme).getId();
        boltId = productService.createProduct(product("BOLT", 10)).getId();
        nutId = productService.createProduct(product("NUT", 0)).getId();
    }

    private static Product product(String sku, int quantity) {
        return new Product(null, sku, sku, null, new BigDecimal("1.00"), quantity, null, null);
    }

    private PurchaseOrderRequest request(PurchaseOrderRequest.Line... lines) {
        return new PurchaseOrderRequest(acmeId, "Weekly order", List.of(lines), null);
    }

    private static PurchaseOrderRequest.Line line(Long productId, int quantity, String unitCost) {
        return new PurchaseOrderRequest.Line(productId, quantity, unitCost == null ? null : new BigDecimal(unitCost));
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private int stockOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getQuantity();
    }

    private Long orderedPurchaseOrder() {
        Long id = purchaseOrderService.createPurchaseOrder(request(line(boltId, 100, "0.25"), line(nutId, 40, null))).getId();
        purchaseOrderService.changeStatus(id, PurchaseOrderStatus.ORDERED);
        flushAndClear();
        return id;
    }

    private PurchaseOrderLine lineFor(PurchaseOrder order, Long productId) {
        return order.getLines().stream().filter(l -> l.getProduct().getId().equals(productId)).findFirst().orElseThrow();
    }

    @Test
    void draftIsCreatedWithLinesAndTotal_andCanBeEdited() {
        PurchaseOrder draft = purchaseOrderService.createPurchaseOrder(request(line(boltId, 100, "0.25")));
        flushAndClear();

        PurchaseOrder loaded = purchaseOrderService.getPurchaseOrder(draft.getId());
        assertThat(loaded.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(loaded.getSupplier().getName()).isEqualTo("Acme");
        assertThat(loaded.getTotalCost()).isEqualByComparingTo("25.00");

        purchaseOrderService.updatePurchaseOrder(draft.getId(), request(line(boltId, 20, null), line(nutId, 5, "1.10")));
        flushAndClear();

        PurchaseOrder edited = purchaseOrderService.getPurchaseOrder(draft.getId());
        assertThat(edited.getLines()).extracting(PurchaseOrderLine::getQuantityOrdered).containsExactly(20, 5);
        assertThat(edited.getTotalCost()).isEqualByComparingTo("5.50");
    }

    @Test
    void rejectsDuplicateProducts_unknownSuppliers_andEditingAfterOrdering() {
        assertThatThrownBy(() -> purchaseOrderService.createPurchaseOrder(request(line(boltId, 1, null), line(boltId, 2, null))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> purchaseOrderService.createPurchaseOrder(
                new PurchaseOrderRequest(9999L, null, List.of(), null)))
                .isInstanceOf(BadRequestException.class);

        Long id = orderedPurchaseOrder();
        assertThatThrownBy(() -> purchaseOrderService.updatePurchaseOrder(id, request(line(boltId, 1, null))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anEmptyDraftCannotBeOrdered() {
        Long id = purchaseOrderService.createPurchaseOrder(request()).getId();

        assertThatThrownBy(() -> purchaseOrderService.changeStatus(id, PurchaseOrderStatus.ORDERED))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void partialReceiptsAddStock_writeReceiptMovements_andFinishAsReceived() {
        Long id = orderedPurchaseOrder();
        PurchaseOrder ordered = purchaseOrderService.getPurchaseOrder(id);
        assertThat(ordered.getOrderedAt()).isNotNull();
        Long boltLine = lineFor(ordered, boltId).getId();
        Long nutLine = lineFor(ordered, nutId).getId();

        purchaseOrderService.receive(id, new ReceiptRequest(List.of(new ReceiptRequest.Line(boltLine, 60)), "First pallet"));
        flushAndClear();

        PurchaseOrder partial = purchaseOrderService.getPurchaseOrder(id);
        assertThat(partial.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(lineFor(partial, boltId).getQuantityRemaining()).isEqualTo(40);
        assertThat(stockOf(boltId)).isEqualTo(70);

        List<StockMovement> receipts = stockMovementRepository.search(boltId, MovementReason.RECEIPT, PageRequest.of(0, 10));
        assertThat(receipts).singleElement().satisfies(m -> {
            assertThat(m.getDelta()).isEqualTo(60);
            assertThat(m.getPurchaseOrderId()).isEqualTo(id);
            assertThat(m.getNote()).isEqualTo("Received on purchase order #" + id + ": First pallet");
        });

        purchaseOrderService.receive(id, new ReceiptRequest(List.of(
                new ReceiptRequest.Line(boltLine, 40), new ReceiptRequest.Line(nutLine, 40)), null));
        flushAndClear();

        assertThat(purchaseOrderService.getPurchaseOrder(id).getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(stockOf(boltId)).isEqualTo(110);
        assertThat(stockOf(nutId)).isEqualTo(40);
        // The ledger still adds up to the stock on hand.
        assertThat(stockMovementRepository.sumDeltaByProductId(boltId)).isEqualTo(110);
    }

    @Test
    void overReceiving_unknownLines_andRepeatedLinesBookNothing() {
        Long id = orderedPurchaseOrder();
        PurchaseOrder ordered = purchaseOrderService.getPurchaseOrder(id);
        Long boltLine = lineFor(ordered, boltId).getId();
        Long nutLine = lineFor(ordered, nutId).getId();

        // The nut line is fine but the bolt line asks for too many, so neither is booked.
        assertThatThrownBy(() -> purchaseOrderService.receive(id, new ReceiptRequest(List.of(
                new ReceiptRequest.Line(nutLine, 10), new ReceiptRequest.Line(boltLine, 101)), null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> purchaseOrderService.receive(id, new ReceiptRequest(List.of(
                new ReceiptRequest.Line(9999L, 1)), null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> purchaseOrderService.receive(id, new ReceiptRequest(List.of(
                new ReceiptRequest.Line(nutLine, 1), new ReceiptRequest.Line(nutLine, 1)), null)))
                .isInstanceOf(BadRequestException.class);
        flushAndClear();

        assertThat(stockOf(nutId)).isZero();
        assertThat(stockOf(boltId)).isEqualTo(10);
    }

    @Test
    void draftsCannotReceive_andStatusRulesHold() {
        Long draftId = purchaseOrderService.createPurchaseOrder(request(line(boltId, 5, null))).getId();
        Long lineId = purchaseOrderService.getPurchaseOrder(draftId).getLines().get(0).getId();
        assertThatThrownBy(() -> purchaseOrderService.receive(draftId, new ReceiptRequest(List.of(
                new ReceiptRequest.Line(lineId, 1)), null)))
                .isInstanceOf(ConflictException.class);

        Long id = orderedPurchaseOrder();
        Long boltLine = lineFor(purchaseOrderService.getPurchaseOrder(id), boltId).getId();
        purchaseOrderService.receive(id, new ReceiptRequest(List.of(new ReceiptRequest.Line(boltLine, 1)), null));

        // Something arrived, so it can't be cancelled any more, only closed.
        assertThatThrownBy(() -> purchaseOrderService.changeStatus(id, PurchaseOrderStatus.CANCELLED))
                .isInstanceOf(ConflictException.class);
        assertThat(purchaseOrderService.changeStatus(id, PurchaseOrderStatus.CLOSED).getStatus())
                .isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThatThrownBy(() -> purchaseOrderService.changeStatus(id, PurchaseOrderStatus.ORDERED))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyDraftsAndCancelledOrdersCanBeDeleted_andTheyKeepProductsAndSuppliersInUse() {
        Long id = orderedPurchaseOrder();
        assertThatThrownBy(() -> purchaseOrderService.deletePurchaseOrder(id)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> productService.deleteProduct(boltId)).isInstanceOf(ProductInUseException.class);
        assertThatThrownBy(() -> supplierService.deleteSupplier(acmeId)).isInstanceOf(ConflictException.class);

        purchaseOrderService.changeStatus(id, PurchaseOrderStatus.CANCELLED);
        purchaseOrderService.deletePurchaseOrder(id);
        flushAndClear();

        assertThatThrownBy(() -> purchaseOrderService.getPurchaseOrder(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addToDraft_reusesTheSuppliersDraft_andSkipsProductsAlreadyOnIt() {
        PurchaseOrderService.DraftAddition first = purchaseOrderService.addToDraft(acmeId, boltId, 50);
        PurchaseOrderService.DraftAddition second = purchaseOrderService.addToDraft(acmeId, nutId, 20);
        PurchaseOrderService.DraftAddition again = purchaseOrderService.addToDraft(acmeId, boltId, 99);
        flushAndClear();

        assertThat(first.lineAdded()).isTrue();
        assertThat(second.purchaseOrder().getId()).isEqualTo(first.purchaseOrder().getId());
        assertThat(again.lineAdded()).isFalse();
        assertThat(purchaseOrderService.getPurchaseOrder(first.purchaseOrder().getId()).getLines())
                .extracting(PurchaseOrderLine::getQuantityOrdered).containsExactly(50, 20);
    }

    @Test
    void listIsNewestFirst_andFiltersByStatus() {
        Long draftId = purchaseOrderService.createPurchaseOrder(request(line(boltId, 1, null))).getId();
        Long orderedId = orderedPurchaseOrder();

        assertThat(purchaseOrderService.searchPurchaseOrders(null, null, null, null).getContent())
                .extracting(PurchaseOrder::getId).containsExactly(orderedId, draftId);
        assertThat(purchaseOrderService.searchPurchaseOrders(PurchaseOrderStatus.DRAFT, acmeId, 0, 10).getContent())
                .extracting(PurchaseOrder::getId).containsExactly(draftId);
    }
}
