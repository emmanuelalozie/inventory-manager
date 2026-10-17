package com.example.inventix.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One line of a made product's bill of materials: one unit of the product uses {@code quantity} of the component.
 */
@Entity
@Table(name = "product_components")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class ProductComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    @ToString.Exclude
    private Product component;

    @Column(nullable = false)
    private int quantity;
}
