package com.example.inventix.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    @ToString.Exclude
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @ToString.Exclude
    private Product product;

    @Column(name = "quantity_ordered", nullable = false)
    private int quantityOrdered;

    @Column(name = "quantity_received", nullable = false)
    private int quantityReceived;

    // Outstanding quantity that was cancelled (it won't arrive). received + cancelled <= ordered.
    @Column(name = "quantity_cancelled", nullable = false)
    private int quantityCancelled;

    // What the supplier charges per unit, if known. Not the product's selling price.
    @Column(name = "unit_cost", precision = 12, scale = 2)
    private BigDecimal unitCost;

    public int getQuantityRemaining() {
        return quantityOrdered - quantityReceived - quantityCancelled;
    }

    /** Cost of what was ordered and not cancelled. */
    public BigDecimal getLineTotal() {
        return unitCost == null ? null : unitCost.multiply(BigDecimal.valueOf(quantityOrdered - quantityCancelled));
    }
}
