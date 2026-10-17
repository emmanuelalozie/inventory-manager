package com.example.inventix.service;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;

import java.util.List;

public interface ProductService {
    /**
     * Creates a product. A starting quantity above 0 is recorded as an ADJUSTMENT movement ("Initial stock").
     */
    Product createProduct(Product product);

    /**
     * Updates name, SKU, description and price. The quantity is not editable here: a null quantity or the
     * stored quantity is accepted and ignored, anything else is rejected.
     *
     * @throws com.example.inventix.exception.QuantityChangeNotAllowedException if the quantity differs
     */
    Product updateProduct(Long id, Product product);

    Product getProductById(Long id);

    List<Product> getAllProducts();

    List<Product> getLowStockProducts(int threshold);

    /**
     * The only way to change a product's stock: changes it by {@code delta} (positive to add, negative to remove)
     * and writes a stock movement in the same transaction.
     *
     * @param note    optional, except for {@link MovementReason#ADJUSTMENT} where it is required
     * @param orderId the order that caused the change, or null
     * @throws IllegalArgumentException if delta is 0, the reason is missing, or an adjustment has no note
     * @throws com.example.inventix.exception.InsufficientStockException if the stock would go below zero
     */
    Product adjustStock(Long productId, int delta, MovementReason reason, String note, Long orderId);

    void deleteProduct(Long id);
}
