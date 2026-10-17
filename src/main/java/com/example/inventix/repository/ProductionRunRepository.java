package com.example.inventix.repository;

import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Runs are loaded with their product (a to-one fetch, so paging stays in the database).
public interface ProductionRunRepository extends JpaRepository<ProductionRun, Long> {

    @Override
    @EntityGraph(attributePaths = "product")
    Optional<ProductionRun> findById(Long id);

    @EntityGraph(attributePaths = "product")
    @Query(value = "select r from ProductionRun r"
            + " where (:status is null or r.status = :status) and (:productId is null or r.product.id = :productId)",
            countQuery = "select count(r) from ProductionRun r"
            + " where (:status is null or r.status = :status) and (:productId is null or r.product.id = :productId)")
    Page<ProductionRun> search(@Param("status") ProductionRunStatus status, @Param("productId") Long productId,
                               Pageable pageable);

    /** The newest planned run for a product, which Restock reuses instead of planning another. */
    @EntityGraph(attributePaths = "product")
    Optional<ProductionRun> findFirstByProductIdAndStatusOrderByIdDesc(Long productId, ProductionRunStatus status);

    /** Units in planned runs, per product. */
    @Query("select r.product.id, sum(r.quantity) from ProductionRun r"
            + " where r.status = com.example.inventix.model.ProductionRunStatus.PLANNED and r.product.id in :productIds"
            + " group by r.product.id")
    List<Object[]> sumPlannedQuantities(@Param("productIds") Collection<Long> productIds);
}
