package com.example.inventix.service;

import com.example.inventix.model.Product;

import java.util.List;

public interface ProductService {
    Product createProduct(Product product);

    Product updateProduct(Long id, Product product);

    Product getProductById(Long id);

    List<Product> getAllProducts();

    List<Product> getLowStockProducts(int threshold);

    /**
     * Changes the stock of a product by {@code delta} (positive to restock, negative to remove).
     *
     * @throws com.example.inventix.exception.InsufficientStockException if the stock would go below zero
     */
    Product adjustStock(Long id, int delta);

    void deleteProduct(Long id);
}
