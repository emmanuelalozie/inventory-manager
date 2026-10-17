package com.example.inventix.dto;

import com.example.inventix.model.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Product as returned by the API. The version is included so clients can send it back on PUT
 * and have a stale edit rejected (optimistic locking).
 */
public record ProductResponse(
        Long id,
        String name,
        String sku,
        String description,
        BigDecimal price,
        Integer quantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getDescription(),
                product.getPrice(),
                product.getQuantity(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getVersion());
    }

    public static List<ProductResponse> fromAll(List<Product> products) {
        return products.stream().map(ProductResponse::from).toList();
    }
}
