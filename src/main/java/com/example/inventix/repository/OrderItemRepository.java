package com.example.inventix.repository;

import com.example.inventix.model.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    boolean existsByProductId(Long productId);

    /**
     * Units and revenue per product over items of SHIPPED and DELIVERED orders created in [start, endExclusive)
     * (null means open), most units first.
     */
    @Query("select i.product.id, i.product.name, i.product.sku, sum(i.quantity), coalesce(sum(i.subtotal), 0)"
            + " from OrderItem i"
            + " where i.order.status in (com.example.inventix.model.OrderStatus.SHIPPED,"
            + " com.example.inventix.model.OrderStatus.DELIVERED)"
            + " and (:start is null or i.order.createdAt >= :start)"
            + " and (:endExclusive is null or i.order.createdAt < :endExclusive)"
            + " group by i.product.id, i.product.name, i.product.sku"
            + " order by sum(i.quantity) desc, coalesce(sum(i.subtotal), 0) desc")
    List<Object[]> topSellers(@Param("start") LocalDateTime start,
                              @Param("endExclusive") LocalDateTime endExclusive,
                              Pageable pageable);
}
