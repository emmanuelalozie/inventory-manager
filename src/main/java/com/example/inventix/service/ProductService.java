package com.example.inventix.service;

import com.example.inventix.model.Product;
import java.util.List;
import java.util.Optional;

public interface ProductService {
    Product createProduct(Product product);
    Product updateProduct(Long id, Product product);
    Optional<Product> getProductById(Long id);
    List<Product> getAllProducts();
    void deleteProduct(Long id);
}