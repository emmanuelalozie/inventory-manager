package com.example.inventix.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    // Back-reference: left out of the JSON to avoid an Order -> OrderItem -> Order loop.
    @ManyToOne
    @JoinColumn(name = "order_id")
    @JsonBackReference
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
     * Exposes the owning order's id in the JSON without serializing the whole order.
     */
    @JsonProperty("orderId")
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
