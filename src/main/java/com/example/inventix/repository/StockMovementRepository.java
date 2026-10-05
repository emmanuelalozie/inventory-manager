package com.example.inventix.repository;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Movements newest first, optionally filtered by product and/or reason (null means no filter).
     * The product is fetched with each movement so the results can be mapped after the transaction.
     */
    @Query("select m from StockMovement m join fetch m.product p"
            + " where (:productId is null or p.id = :productId)"
            + " and (:reason is null or m.reason = :reason)"
            + " order by m.createdAt desc, m.id desc")
    List<StockMovement> search(@Param("productId") Long productId,
                               @Param("reason") MovementReason reason,
                               Pageable pageable);

    List<StockMovement> findByOrderId(Long orderId);

    @Query("select coalesce(sum(m.delta), 0) from StockMovement m where m.product.id = :productId")
    long sumDeltaByProductId(@Param("productId") Long productId);

    @Modifying
    @Query("delete from StockMovement m where m.product.id = :productId")
    void deleteByProductId(@Param("productId") Long productId);
}
