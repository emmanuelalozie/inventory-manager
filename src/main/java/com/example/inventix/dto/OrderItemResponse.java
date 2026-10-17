package com.example.inventix.dto;

import com.example.inventix.model.OrderItem;
import com.example.inventix.model.Product;

import java.math.BigDecimal;
import java.util.List;

/**
 * Order item as returned by the API. The product is flattened to its id, name and SKU;
 * clients that need the current stock read it from /api/products.
 */
public record OrderItemResponse(
        Long id,
        Long orderId,
        Long productId,
        String productName,
        String productSku,
        int quantity,
        BigDecimal pricePerUnit,
        BigDecimal subtotal) {

    public static OrderItemResponse from(OrderItem item) {
        Product product = item.getProduct();
        return new OrderItemResponse(
                item.getId(),
                item.getOrderId(),
                product != null ? product.getId() : null,
                product != null ? product.getName() : null,
                product != null ? product.getSku() : null,
                item.getQuantity(),
                item.getPricePerUnit(),
                item.getSubtotal());
    }

    public static List<OrderItemResponse> fromAll(List<OrderItem> items) {
        return items.stream().map(OrderItemResponse::from).toList();
    }
}
