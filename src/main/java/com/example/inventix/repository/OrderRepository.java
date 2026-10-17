package com.example.inventix.repository;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// Orders are always loaded together with their items and products: the controllers map them to DTOs
// after the transaction has ended (open-in-view is off), so a lazy orderItems list would fail there.
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Override
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    Optional<Order> findById(Long id);

    @Override
    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    List<Order> findAll();

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    List<Order> findByStatus(OrderStatus status);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * One page of order ids, optionally filtered by status. Paging ids first and then loading those orders with
     * {@link #findByIdIn} keeps the LIMIT in the database; paging a fetch join of the items would load every
     * order into memory.
     */
    @Query(value = "select o.id from Order o where :status is null or o.status = :status",
            countQuery = "select count(o) from Order o where :status is null or o.status = :status")
    Page<Long> findIds(@Param("status") OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"orderItems", "orderItems.product"})
    List<Order> findByIdIn(Collection<Long> ids);
}
