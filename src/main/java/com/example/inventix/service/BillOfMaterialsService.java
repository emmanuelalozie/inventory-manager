package com.example.inventix.service;

import com.example.inventix.dto.ComponentsRequest;
import com.example.inventix.model.ProductComponent;

import java.util.List;

/**
 * Bills of materials: which components (other products) one unit of a made product uses.
 */
public interface BillOfMaterialsService {

    /**
     * @throws com.example.inventix.exception.ProductNotFoundException if the product doesn't exist
     */
    List<ProductComponent> getComponents(Long productId);

    /**
     * Replaces the product's components. The product must be made in-house (MAKE); each component must exist, appear
     * once, and not be the product itself or (directly or indirectly) use the product.
     *
     * @throws com.example.inventix.exception.BadRequestException if any of those rules is broken
     */
    List<ProductComponent> replaceComponents(Long productId, List<ComponentsRequest.Line> components);
}
