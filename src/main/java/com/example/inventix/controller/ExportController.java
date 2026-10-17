package com.example.inventix.controller;

import com.example.inventix.export.CsvWriter;
import com.example.inventix.model.Category;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.model.Supplier;
import com.example.inventix.service.CategoryService;
import com.example.inventix.service.DateRange;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.ReportService;
import com.example.inventix.service.StockMovementService;
import com.example.inventix.service.SupplierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CSV downloads (UTF-8 with a byte order mark, so Excel opens them correctly). Same filters as the JSON endpoints.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    /** Movements are exported in pages of this size, so a large ledger isn't loaded in one query. */
    static final int MOVEMENT_BATCH = 500;

    private final ProductService productService;
    private final CategoryService categoryService;
    private final SupplierService supplierService;
    private final StockMovementService stockMovementService;
    private final ReportService reportService;

    @Autowired
    public ExportController(ProductService productService, CategoryService categoryService,
                            SupplierService supplierService, StockMovementService stockMovementService,
                            ReportService reportService) {
        this.productService = productService;
        this.categoryService = categoryService;
        this.supplierService = supplierService;
        this.stockMovementService = stockMovementService;
        this.reportService = reportService;
    }

    @GetMapping("/products.csv")
    public ResponseEntity<String> products() {
        Map<Long, String> categories = categoryService.getAllCategories().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> suppliers = supplierService.getAllSuppliers().stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        CsvWriter csv = new CsvWriter().row("id", "sku", "name", "category", "source", "supplier", "price",
                "unit_cost", "quantity", "reorder_point", "reorder_quantity", "value_at_price", "value_at_cost");
        for (Product p : productService.searchProducts(null, null, null, null, "name").getContent()) {
            BigDecimal qty = BigDecimal.valueOf(p.getQuantity());
            csv.row(p.getId(), p.getSku(), p.getName(), categories.get(p.getCategoryId()), p.getSource(),
                    suppliers.get(p.getSupplierId()), p.getPrice(), p.getUnitCost(), p.getQuantity(),
                    p.getReorderPoint(), p.getReorderQuantity(), p.getPrice().multiply(qty),
                    p.getUnitCost() == null ? null : p.getUnitCost().multiply(qty));
        }
        return download("products", csv);
    }

    @GetMapping("/stock-movements.csv")
    public ResponseEntity<String> stockMovements(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementReason reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        DateRange range = DateRange.of(from, to);
        CsvWriter csv = new CsvWriter().row("id", "created_at", "sku", "product", "reason", "delta", "note",
                "order_id", "purchase_order_id", "production_run_id");
        Page<StockMovement> batch;
        int page = 0;
        do {
            batch = stockMovementService.searchMovements(productId, reason, range, page++, MOVEMENT_BATCH);
            for (StockMovement m : batch.getContent()) {
                csv.row(m.getId(), m.getCreatedAt(), m.getProduct().getSku(), m.getProduct().getName(), m.getReason(),
                        m.getDelta(), m.getNote(), m.getOrderId(), m.getPurchaseOrderId(), m.getProductionRunId());
            }
        } while (batch.hasNext());
        return download("stock-movements", csv);
    }

    @GetMapping("/stock-value.csv")
    public ResponseEntity<String> stockValue() {
        ReportService.StockValue value = reportService.stockValue();
        CsvWriter csv = new CsvWriter().row("category", "products", "units", "value_at_price", "value_at_cost",
                "products_without_cost");
        for (ReportService.CategoryValue c : value.categories()) {
            csv.row(c.categoryName() == null ? "(no category)" : c.categoryName(), c.products(), c.units(),
                    c.valueAtPrice(), c.valueAtCost(), c.productsWithoutCost());
        }
        ReportService.Totals t = value.total();
        csv.row("Total", t.products(), t.units(), t.valueAtPrice(), t.valueAtCost(), t.productsWithoutCost());
        return download("stock-value", csv);
    }

    @GetMapping("/top-sellers.csv")
    public ResponseEntity<String> topSellers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = ReportController.DEFAULT_TOP_SELLERS) int limit) {
        CsvWriter csv = new CsvWriter().row("rank", "sku", "product", "units_sold", "revenue");
        List<ReportService.TopSeller> sellers = reportService.topSellers(DateRange.of(from, to), limit);
        for (int i = 0; i < sellers.size(); i++) {
            ReportService.TopSeller s = sellers.get(i);
            csv.row(i + 1, s.sku(), s.name(), s.unitsSold(), s.revenue());
        }
        return download("top-sellers", csv);
    }

    private static ResponseEntity<String> download(String name, CsvWriter csv) {
        String fileName = name + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(csv.toString());
    }
}
