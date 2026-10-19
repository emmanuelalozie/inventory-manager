package com.example.inventix.service;

import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.model.Category;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Order;
import com.example.inventix.model.OrderItem;
import com.example.inventix.model.OrderStatus;
import com.example.inventix.model.Product;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.model.Supplier;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.impl.CategoryServiceImpl;
import com.example.inventix.service.impl.OrderItemServiceImpl;
import com.example.inventix.service.impl.CustomerServiceImpl;
import com.example.inventix.service.impl.OrderServiceImpl;
import com.example.inventix.service.impl.ProductServiceImpl;
import com.example.inventix.service.impl.PurchaseOrderServiceImpl;
import com.example.inventix.service.impl.ReportServiceImpl;
import com.example.inventix.service.impl.StockMovementServiceImpl;
import com.example.inventix.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Categories, unit cost and the reports, against H2 with the real migrations.
 */
@DataJpaTest
@Import({ProductServiceImpl.class, CategoryServiceImpl.class, SupplierServiceImpl.class, PurchaseOrderServiceImpl.class,
        OrderServiceImpl.class, CustomerServiceImpl.class, OrderItemServiceImpl.class, StockMovementServiceImpl.class, ReportServiceImpl.class})
class ReportIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductService productService;

    @Autowired
    private StockMovementService stockMovementService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private SupplierService supplierService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Long cablesId;
    private Long cableId;   // in Cables, 10 x 2.00, cost 0.50
    private Long hubId;     // in Cables, 4 x 30.00, no cost
    private Long monitorId; // no category, 2 x 200.00, cost 150.00

    @BeforeEach
    void setUp() {
        cablesId = categoryService.createCategory("Cables").getId();
        cableId = productService.createProduct(product("Cable", "CBL", "2.00", 10, cablesId, "0.50")).getId();
        hubId = productService.createProduct(product("Hub", "HUB", "30.00", 4, cablesId, null)).getId();
        monitorId = productService.createProduct(product("Monitor", "MON", "200.00", 2, null, "150.00")).getId();
    }

    private static Product product(String name, String sku, String price, int qty, Long categoryId, String cost) {
        Product product = new Product(null, name, sku, null, new BigDecimal(price), qty, null, null);
        product.setCategoryId(categoryId);
        product.setUnitCost(cost == null ? null : new BigDecimal(cost));
        return product;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static OrderItem item(Long productId, int quantity) {
        Product product = new Product();
        product.setId(productId);
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }

    // Moves every movement of a product, and every order, to the given day (the app always stamps "now").
    private void backdateMovements(Long productId, LocalDateTime when) {
        entityManager.getEntityManager()
                .createQuery("update StockMovement m set m.createdAt = :when where m.product.id = :productId")
                .setParameter("when", when).setParameter("productId", productId).executeUpdate();
    }

    private void backdateOrder(Long orderId, LocalDateTime when) {
        entityManager.getEntityManager().createQuery("update Order o set o.createdAt = :when where o.id = :id")
                .setParameter("when", when).setParameter("id", orderId).executeUpdate();
    }

    @Test
    void categoriesAreUniqueIgnoringCase_countProducts_andCantBeDeletedWhileUsed() {
        assertThatThrownBy(() -> categoryService.createCategory(" cables ")).isInstanceOf(ConflictException.class);
        assertThat(categoryService.countProductsByCategory()).containsEntry(cablesId, 2L);
        assertThatThrownBy(() -> categoryService.deleteCategory(cablesId)).isInstanceOf(ConflictException.class);

        Long emptyId = categoryService.createCategory("Spare").getId();
        categoryService.renameCategory(emptyId, "Spares", null);
        assertThat(categoryService.getAllCategories()).extracting(Category::getName).containsExactly("Cables", "Spares");
        categoryService.deleteCategory(emptyId);
    }

    @Test
    void productsRejectUnknownCategories_andCanBeFilteredByCategory() {
        assertThatThrownBy(() -> productService.createProduct(product("X", "X-1", "1.00", 0, 9999L, null)))
                .isInstanceOf(BadRequestException.class);

        assertThat(productService.searchProducts(null, cablesId, null, null, "sku").getContent())
                .extracting(Product::getSku).containsExactly("CBL", "HUB");
        assertThat(productService.searchProducts("hu", cablesId, null, null, null).getContent())
                .extracting(Product::getSku).containsExactly("HUB");
    }

    @Test
    void stockValue_isGroupedByCategory_withProductsWithoutCostCounted() {
        ReportService.StockValue value = reportService.stockValue();

        assertThat(value.categories())
                .extracting(ReportService.CategoryValue::categoryName, ReportService.CategoryValue::products,
                        ReportService.CategoryValue::units)
                .containsExactly(tuple("Cables", 2L, 14L), tuple(null, 1L, 2L));
        ReportService.CategoryValue cables = value.categories().get(0);
        assertThat(cables.valueAtPrice()).isEqualByComparingTo("140.00");
        assertThat(cables.valueAtCost()).isEqualByComparingTo("5.00");
        assertThat(cables.productsWithoutCost()).isEqualTo(1);
        assertThat(value.total().valueAtPrice()).isEqualByComparingTo("540.00");
        assertThat(value.total().valueAtCost()).isEqualByComparingTo("305.00");
        assertThat(value.total().products()).isEqualTo(3);
    }

    @Test
    void receivingAPurchaseOrderLineWithACost_updatesTheProductsUnitCost() {
        Supplier acme = new Supplier();
        acme.setName("Acme");
        Long acmeId = supplierService.createSupplier(acme).getId();
        Long poId = purchaseOrderService.createPurchaseOrder(new PurchaseOrderRequest(acmeId, null,
                List.of(new PurchaseOrderRequest.Line(hubId, 5, new BigDecimal("18.40"))), null)).getId();
        purchaseOrderService.changeStatus(poId, PurchaseOrderStatus.ORDERED);
        Long lineId = purchaseOrderService.getPurchaseOrder(poId).getLines().get(0).getId();

        purchaseOrderService.receive(poId, new ReceiptRequest(List.of(new ReceiptRequest.Line(lineId, 2)), null));
        flushAndClear();

        Product hub = productRepository.findById(hubId).orElseThrow();
        assertThat(hub.getUnitCost()).isEqualByComparingTo("18.40");
        assertThat(hub.getQuantity()).isEqualTo(6);
    }

    @Test
    void movementsCanBeFilteredByDays_bothEndsIncluded() {
        productService.adjustStock(cableId, 5, MovementReason.ADJUSTMENT, "Recount", null);
        flushAndClear();
        backdateMovements(cableId, LocalDateTime.of(2026, 9, 30, 23, 59));
        backdateMovements(hubId, LocalDateTime.of(2026, 10, 1, 0, 0));
        backdateMovements(monitorId, LocalDateTime.of(2026, 10, 31, 23, 59, 59));
        flushAndClear();

        DateRange october = DateRange.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(stockMovementService.searchMovements(null, null, october, 0, 50).getContent())
                .extracting(m -> m.getProduct().getSku()).containsExactlyInAnyOrder("HUB", "MON");
        assertThat(stockMovementService.searchMovements(null, null, DateRange.of(null, LocalDate.of(2026, 9, 30)), 0, 50)
                .getTotalElements()).isEqualTo(2); // the cable's initial stock and its recount
        assertThatThrownBy(() -> DateRange.of(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BadRequestException.class);

        List<ReportService.MovementSummary> summary = reportService.movementSummary(october);
        assertThat(summary).singleElement().satisfies(s -> {
            assertThat(s.reason()).isEqualTo(MovementReason.ADJUSTMENT);
            assertThat(s.movements()).isEqualTo(2);
            assertThat(s.unitsIn()).isEqualTo(6);
            assertThat(s.unitsOut()).isZero();
        });
    }

    @Test
    void topSellers_countOnlyShippedAndDeliveredOrdersInTheRange() {
        Long shipped = orderService.createOrderWithItems(new Order(), List.of(item(cableId, 3), item(hubId, 1))).getId();
        orderService.updateOrderStatus(shipped, OrderStatus.SHIPPED);
        Long delivered = orderService.createOrderWithItems(new Order(), List.of(item(cableId, 2))).getId();
        orderService.updateOrderStatus(delivered, OrderStatus.SHIPPED);
        orderService.updateOrderStatus(delivered, OrderStatus.DELIVERED);
        orderService.createOrderWithItems(new Order(), List.of(item(hubId, 3))); // pending: not sold yet
        Long old = orderService.createOrderWithItems(new Order(), List.of(item(monitorId, 1))).getId();
        orderService.updateOrderStatus(old, OrderStatus.SHIPPED);
        flushAndClear();
        backdateOrder(old, LocalDateTime.of(2020, 1, 1, 12, 0));
        flushAndClear();

        List<ReportService.TopSeller> sellers = reportService.topSellers(DateRange.of(LocalDate.of(2021, 1, 1), null), 10);

        assertThat(sellers).extracting(ReportService.TopSeller::sku, ReportService.TopSeller::unitsSold)
                .containsExactly(tuple("CBL", 5L), tuple("HUB", 1L));
        assertThat(sellers.get(0).revenue()).isEqualByComparingTo("10.00");
        assertThat(reportService.topSellers(DateRange.ALL, 1)).extracting(ReportService.TopSeller::sku).containsExactly("CBL");
        assertThatThrownBy(() -> reportService.topSellers(DateRange.ALL, 0)).isInstanceOf(BadRequestException.class);
    }
}
