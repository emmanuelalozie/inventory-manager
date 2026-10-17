package com.example.inventix.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One row of the stock ledger: every change to a product's quantity writes one of these
 * (see ProductServiceImpl), so the deltas of a product add up to its current quantity.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class StockMovement {

    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude
    private Product product;

    // Positive adds stock, negative removes it. Never 0.
    @Column(nullable = false)
    private int delta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MovementReason reason;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    // Plain ids without foreign keys: pending orders can be deleted while their movements stay in the
    // ledger, and the purchase_orders and locations tables don't exist yet.
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(name = "location_id")
    private Long locationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public StockMovement(Product product, int delta, MovementReason reason, String note, Long orderId) {
        this.product = product;
        this.delta = delta;
        this.reason = reason;
        this.note = note;
        this.orderId = orderId;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
