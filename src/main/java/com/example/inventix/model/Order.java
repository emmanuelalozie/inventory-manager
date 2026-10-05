package com.example.inventix.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
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

// "order" is a reserved word in SQL (and in H2 2.x), so the table needs a different name.
@Entity
@Table(name = "orders")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Plain varchar column holding the enum name. Without @JdbcTypeCode, Hibernate 6 may create
    // a native ENUM column on databases that support one.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    @ToString.Exclude
    private List<OrderItem> orderItems = new ArrayList<>();

    /**
     * Adds an item to this order, keeps both sides of the relationship in sync
     * and recalculates the order total.
     */
    public void addItem(OrderItem item) {
        if (orderItems == null) {
            orderItems = new ArrayList<>();
        }
        orderItems.add(item);
        item.setOrder(this);
        recalculateTotal();
    }

    /**
     * Removes an item from this order (orphan removal deletes it) and recalculates the order total.
     */
    public void removeItem(OrderItem item) {
        if (orderItems != null) {
            orderItems.remove(item);
        }
        item.setOrder(null);
        recalculateTotal();
    }

    /**
     * Sets totalAmount to the sum of the item subtotals (quantity x unit price).
     */
    public void recalculateTotal() {
        if (orderItems == null) {
            totalAmount = BigDecimal.ZERO;
            return;
        }
        totalAmount = orderItems.stream()
                .map(OrderItem::getSubtotal)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
