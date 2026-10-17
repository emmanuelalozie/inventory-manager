package com.example.inventix.dto;

import com.example.inventix.model.Product;
import com.example.inventix.model.ProductSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for creating or updating a product. id, createdAt and updatedAt are managed by the server,
 * so they aren't accepted here. version is optional and only used on PUT to reject stale edits.
 * <p>
 * quantity is the starting stock on POST (required). On PUT it is optional: leave it out, or send the stored
 * value; a different value is rejected, because stock only changes through PATCH /api/products/{id}/stock.
 * <p>
 * source (BUY by default), supplierId, reorderPoint and reorderQuantity describe how the product is replenished;
 * categoryId and unitCost are optional too. PUT replaces all of them like the other fields, so a client that
 * leaves them out clears them.
 */
public record ProductRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @NotBlank(message = "SKU is required")
        @Size(max = 64, message = "SKU must be at most 64 characters")
        String sku,

        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        @NotNull(message = "Price is required")
        @PositiveOrZero(message = "Price must be zero or greater")
        BigDecimal price,

        @NotNull(message = "Quantity is required", groups = OnCreate.class)
        @PositiveOrZero(message = "Quantity must be zero or greater")
        Integer quantity,

        ProductSource source,

        Long supplierId,

        @PositiveOrZero(message = "Reorder point must be zero or greater")
        Integer reorderPoint,

        @Positive(message = "Reorder quantity must be greater than 0")
        Integer reorderQuantity,

        Long categoryId,

        @PositiveOrZero(message = "Unit cost must be zero or greater")
        BigDecimal unitCost,

        Long version) {

    /** Validation group for rules that only apply when creating a product. */
    public interface OnCreate {
    }

    public Product toEntity() {
        Product product = new Product();
        product.setName(name);
        product.setSku(sku);
        product.setDescription(description);
        product.setPrice(price);
        product.setQuantity(quantity);
        product.setSource(source == null ? ProductSource.BUY : source);
        product.setSupplierId(supplierId);
        product.setReorderPoint(reorderPoint);
        product.setReorderQuantity(reorderQuantity);
        product.setCategoryId(categoryId);
        product.setUnitCost(unitCost);
        product.setVersion(version);
        return product;
    }
}
