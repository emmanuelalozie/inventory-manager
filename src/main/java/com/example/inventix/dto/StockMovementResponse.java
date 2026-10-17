package com.example.inventix.dto;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stock ledger entry as returned by the API. The product is flattened to its id, name and SKU.
 */
public record StockMovementResponse(
        Long id,
        Long productId,
        String productName,
        String productSku,
        int delta,
        MovementReason reason,
        String note,
        Long orderId,
        Long purchaseOrderId,
        Long locationId,
        LocalDateTime createdAt) {

    public static StockMovementResponse from(StockMovement movement) {
        Product product = movement.getProduct();
        return new StockMovementResponse(
                movement.getId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                product != null ? product.getSku() : null,
                movement.getDelta(),
                movement.getReason(),
                movement.getNote(),
                movement.getOrderId(),
                movement.getPurchaseOrderId(),
                movement.getLocationId(),
                movement.getCreatedAt());
    }

    public static List<StockMovementResponse> fromAll(List<StockMovement> movements) {
        return movements.stream().map(StockMovementResponse::from).toList();
    }
}
