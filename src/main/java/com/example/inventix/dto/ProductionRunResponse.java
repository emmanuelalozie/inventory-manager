package com.example.inventix.dto;

import com.example.inventix.model.Product;
import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Production run as returned by the API. The product must already be loaded (ProductionRunRepository fetches it).
 */
public record ProductionRunResponse(
        Long id,
        Long productId,
        String productName,
        String productSku,
        int quantity,
        ProductionRunStatus status,
        String notes,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    public static ProductionRunResponse from(ProductionRun run) {
        Product product = run.getProduct();
        return new ProductionRunResponse(run.getId(), product.getId(), product.getName(), product.getSku(),
                run.getQuantity(), run.getStatus(), run.getNotes(), run.getCompletedAt(), run.getCreatedAt(),
                run.getUpdatedAt(), run.getVersion());
    }

    public static List<ProductionRunResponse> fromAll(List<ProductionRun> runs) {
        return runs.stream().map(ProductionRunResponse::from).toList();
    }
}
