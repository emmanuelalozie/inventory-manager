package com.example.inventix.repository;

import com.example.inventix.model.Order;
import com.example.inventix.model.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
