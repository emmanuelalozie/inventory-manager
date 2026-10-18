package com.example.inventix.dto;

import java.util.List;

/**
 * What can still be returned on an order: per product, the units sold on it, the units already returned and the
 * units that can still be returned.
 */
public record OrderReturnsResponse(Long orderId, List<Line> lines) {

    public record Line(Long productId, String productSku, String productName, int quantitySold, int quantityReturned,
                       int quantityReturnable) {

        public static Line of(Long productId, String productSku, String productName, int sold, int returned) {
            return new Line(productId, productSku, productName, sold, returned, sold - returned);
        }
    }
}
