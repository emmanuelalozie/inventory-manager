package com.example.inventix.repository;

import com.example.inventix.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    List<Product> findByQuantityLessThanEqualOrderByQuantityAsc(Integer threshold);
}
