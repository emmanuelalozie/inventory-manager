package com.example.inventix.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    @ToString.Exclude
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(length = 1000)
    private String notes;

    @Column(name = "ordered_at")
    private LocalDateTime orderedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    @ToString.Exclude
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    public void addLine(PurchaseOrderLine line) {
        lines.add(line);
        line.setPurchaseOrder(this);
    }

    /** Sum of quantity x unit cost over the lines that have a cost. */
    public BigDecimal getTotalCost() {
        return lines.stream()
                .map(PurchaseOrderLine::getLineTotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Nothing is still to come on any line (everything arrived or was cancelled). */
    public boolean isSettled() {
        return !lines.isEmpty() && lines.stream().allMatch(line -> line.getQuantityRemaining() == 0);
    }

    public boolean hasCancelledQuantities() {
        return lines.stream().anyMatch(line -> line.getQuantityCancelled() > 0);
    }

    /**
     * The status once nothing is still to come: RECEIVED if everything ordered arrived, CLOSED if part was
     * cancelled, CANCELLED if nothing arrived at all.
     */
    public PurchaseOrderStatus settledStatus() {
        if (!hasCancelledQuantities()) {
            return PurchaseOrderStatus.RECEIVED;
        }
        return hasReceivedAnything() ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.CANCELLED;
    }

    public boolean hasReceivedAnything() {
        return lines.stream().anyMatch(line -> line.getQuantityReceived() > 0);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
