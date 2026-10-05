package com.example.inventix.controller;

import com.example.inventix.dto.ProductRequest;
import com.example.inventix.dto.ProductResponse;
import com.example.inventix.dto.StockAdjustmentRequest;
import com.example.inventix.model.MovementReason;
import com.example.inventix.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

// Entities are mapped to DTOs here, after the service transaction has committed, so the response
// carries the version and updatedAt that were written on flush.
@RestController
@RequestMapping("/api/products")
public class ProductController {

    static final String DEFAULT_LOW_STOCK_THRESHOLD = "10";

    private final ProductService productService;

    @Autowired
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> getAllProducts() {
        return ProductResponse.fromAll(productService.getAllProducts());
    }

    @GetMapping("/low-stock")
    public List<ProductResponse> getLowStockProducts(
            @RequestParam(defaultValue = DEFAULT_LOW_STOCK_THRESHOLD) int threshold) {
        return ProductResponse.fromAll(productService.getLowStockProducts(threshold));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(ProductResponse.from(productService.getProductById(id)));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Validated({Default.class, ProductRequest.OnCreate.class}) @RequestBody ProductRequest request) {
        ProductResponse createdProduct = ProductResponse.from(productService.createProduct(request.toEntity()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdProduct.id())
                .toUri();
        return ResponseEntity.created(location).body(createdProduct);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(ProductResponse.from(productService.updateProduct(id, request.toEntity())));
    }

    // Manual adjustment: recorded in the stock ledger as ADJUSTMENT with the (required) note.
    @PatchMapping("/{id}/stock")
    public ResponseEntity<ProductResponse> adjustStock(
            @PathVariable Long id,
            @Valid @RequestBody StockAdjustmentRequest request) {
        return ResponseEntity.ok(ProductResponse.from(productService.adjustStock(
                id, request.delta(), MovementReason.ADJUSTMENT, request.note(), null)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
