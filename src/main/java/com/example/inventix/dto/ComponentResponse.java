package com.example.inventix.dto;

import com.example.inventix.model.Product;
import com.example.inventix.model.ProductComponent;

import java.util.List;

/**
 * One component of a made product: how many one unit uses, and how many are in stock now.
 */
public record ComponentResponse(Long componentId, String name, String sku, int quantity, int inStock) {

    public static ComponentResponse from(ProductComponent line) {
        Product component = line.getComponent();
        return new ComponentResponse(component.getId(), component.getName(), component.getSku(), line.getQuantity(),
                component.getQuantity());
    }

    public static List<ComponentResponse> fromAll(List<ProductComponent> lines) {
        return lines.stream().map(ComponentResponse::from).toList();
    }
}
