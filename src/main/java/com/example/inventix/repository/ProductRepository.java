package com.example.inventix.repository;

import com.example.inventix.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    List<Product> findByQuantityLessThanEqualOrderByQuantityAsc(Integer threshold);

    /**
     * Products whose name, SKU or description contains the pattern (a lower-case LIKE pattern with '\' as the
     * escape character), or all products when it is null.
     */
    @Query(value = "select p from Product p where :pattern is null"
            + " or lower(p.name) like :pattern escape '\\'"
            + " or lower(p.sku) like :pattern escape '\\'"
            + " or lower(p.description) like :pattern escape '\\'",
            countQuery = "select count(p) from Product p where :pattern is null"
            + " or lower(p.name) like :pattern escape '\\'"
            + " or lower(p.sku) like :pattern escape '\\'"
            + " or lower(p.description) like :pattern escape '\\'")
    Page<Product> search(@Param("pattern") String pattern, Pageable pageable);
}
