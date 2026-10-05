package com.example.inventix.dto;

import com.example.inventix.model.OrderItem;
import com.example.inventix.model.Product;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for adding an item to an order: {"productId": 1, "quantity": 2}.
 */
public record OrderItemRequest(
        @NotNull(message = "productId is required") Long productId,
        @NotNull(message = "quantity is required") @Positive(message = "quantity must be greater than 0") Integer quantity) {

    public OrderItem toEntity() {
        Product product = new Product();
        product.setId(productId);

        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }
}
