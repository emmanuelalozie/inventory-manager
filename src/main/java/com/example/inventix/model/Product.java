package com.example.inventix.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    @Column(nullable = false)
    private String name;

    @NotBlank(message = "SKU is required")
    @Size(max = 64, message = "SKU must be at most 64 characters")
    @Column(nullable = false, unique = true, length = 64)
    private String sku;

    @Size(max = 1000, message = "Description must be at most 1000 characters")
    @Column(length = 1000)
    private String description;

    @NotNull(message = "Price is required")
    @PositiveOrZero(message = "Price must be zero or greater")
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @NotNull(message = "Quantity is required")
    @PositiveOrZero(message = "Quantity must be zero or greater")
    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Optimistic lock: concurrent stock changes to the same product fail with 409 instead of overselling.
    // Clients may send back the version they read with PUT (ProductRequest); a stale value is rejected
    // (see ProductServiceImpl).
    @Version
    private Long version;

    // Kept without the version so it is always managed by JPA, never set by callers.
    public Product(Long id, String name, String sku, String description, BigDecimal price, Integer quantity,
                   LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.sku = sku;
        this.description = description;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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
