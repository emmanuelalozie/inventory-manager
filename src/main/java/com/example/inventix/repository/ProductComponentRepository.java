package com.example.inventix.repository;

import com.example.inventix.model.ProductComponent;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductComponentRepository extends JpaRepository<ProductComponent, Long> {

    /** A product's components with their products loaded, by component name. */
    @EntityGraph(attributePaths = "component")
    @Query("select c from ProductComponent c where c.productId = :productId order by c.component.name")
    List<ProductComponent> findByProductId(@Param("productId") Long productId);

    @Query("select c.component.id from ProductComponent c where c.productId = :productId")
    List<Long> findComponentIds(@Param("productId") Long productId);

    @Modifying
    @Query("delete from ProductComponent c where c.productId = :productId")
    void deleteByProductId(@Param("productId") Long productId);
}
