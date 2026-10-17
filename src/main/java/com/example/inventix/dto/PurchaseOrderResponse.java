package com.example.inventix.dto;

import com.example.inventix.model.Product;
import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderLine;
import com.example.inventix.model.PurchaseOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Purchase order as returned by the API, with its supplier name and lines embedded.
 * The supplier, lines and products must already be loaded (PurchaseOrderRepository fetches them).
 */
public record PurchaseOrderResponse(
        Long id,
        Long supplierId,
        String supplierName,
        PurchaseOrderStatus status,
        String notes,
        BigDecimal totalCost,
        LocalDateTime orderedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version,
        List<Line> lines) {

    public record Line(
            Long id,
            Long productId,
            String productName,
            String productSku,
            int quantityOrdered,
            int quantityReceived,
            int quantityRemaining,
            BigDecimal unitCost,
            BigDecimal lineTotal) {

        static Line from(PurchaseOrderLine line) {
            Product product = line.getProduct();
            return new Line(line.getId(), product.getId(), product.getName(), product.getSku(),
                    line.getQuantityOrdered(), line.getQuantityReceived(), line.getQuantityRemaining(),
                    line.getUnitCost(), line.getLineTotal());
        }
    }

    public static PurchaseOrderResponse from(PurchaseOrder order) {
        return new PurchaseOrderResponse(
                order.getId(),
                order.getSupplier().getId(),
                order.getSupplier().getName(),
                order.getStatus(),
                order.getNotes(),
                order.getTotalCost(),
                order.getOrderedAt(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getVersion(),
                order.getLines().stream().map(Line::from).toList());
    }

    public static List<PurchaseOrderResponse> fromAll(List<PurchaseOrder> orders) {
        return orders.stream().map(PurchaseOrderResponse::from).toList();
    }
}
