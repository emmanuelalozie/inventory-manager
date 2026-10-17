package com.example.inventix.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Entity
@Table(name = "order_items")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id")
    @ToString.Exclude
    private Order order;

    @ManyToOne
    @JoinColumn(name = "product_id")
    private Product product;

    private int quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal pricePerUnit;

    @Column(precision = 14, scale = 2)
    private BigDecimal subtotal;

    /**
     * The owning order's id, or null if the item isn't attached to an order.
     */
    public Long getOrderId() {
        return order != null ? order.getId() : null;
    }

    /**
     * Sets subtotal to quantity x pricePerUnit.
     */
    public void recalculateSubtotal() {
        subtotal = pricePerUnit == null ? null : pricePerUnit.multiply(BigDecimal.valueOf(quantity));
    }
}
