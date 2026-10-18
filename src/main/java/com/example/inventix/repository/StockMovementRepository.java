package com.example.inventix.repository;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    /**
     * Like {@link #search}, also limited to createdAt in [start, endExclusive) (null means open), with a total count.
     */
    @Query(value = "select m from StockMovement m join fetch m.product p"
            + " where (:productId is null or p.id = :productId)"
            + " and (:reason is null or m.reason = :reason)"
            + " and (:start is null or m.createdAt >= :start)"
            + " and (:endExclusive is null or m.createdAt < :endExclusive)"
            + " order by m.createdAt desc, m.id desc",
            countQuery = "select count(m) from StockMovement m"
            + " where (:productId is null or m.product.id = :productId)"
            + " and (:reason is null or m.reason = :reason)"
            + " and (:start is null or m.createdAt >= :start)"
            + " and (:endExclusive is null or m.createdAt < :endExclusive)")
    Page<StockMovement> searchInRange(@Param("productId") Long productId,
                                      @Param("reason") MovementReason reason,
                                      @Param("start") LocalDateTime start,
                                      @Param("endExclusive") LocalDateTime endExclusive,
                                      Pageable pageable);

    /** Per reason: number of movements, units added, units removed, within [start, endExclusive). */
    @Query("select m.reason, count(m),"
            + " coalesce(sum(case when m.delta > 0 then m.delta else 0 end), 0),"
            + " coalesce(sum(case when m.delta < 0 then -m.delta else 0 end), 0)"
            + " from StockMovement m"
            + " where (:start is null or m.createdAt >= :start)"
            + " and (:endExclusive is null or m.createdAt < :endExclusive)"
            + " group by m.reason")
    List<Object[]> summarizeByReason(@Param("start") LocalDateTime start,
                                     @Param("endExclusive") LocalDateTime endExclusive);

    List<StockMovement> findByOrderId(Long orderId);

    @Query("select coalesce(sum(m.delta), 0) from StockMovement m where m.product.id = :productId")
    long sumDeltaByProductId(@Param("productId") Long productId);

    @Modifying
    @Query("delete from StockMovement m where m.product.id = :productId")
    void deleteByProductId(@Param("productId") Long productId);
}
