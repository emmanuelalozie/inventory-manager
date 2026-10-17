package com.example.inventix.service;

import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;

import java.util.List;

/**
 * Reads the stock ledger. Movements are written only by {@link ProductService#adjustStock}.
 */
public interface StockMovementService {

    int MAX_PAGE_SIZE = 500;

    /**
     * One page of movements, newest first. productId and reason are optional filters.
     *
     * @throws IllegalArgumentException if page is negative or size is not between 1 and {@link #MAX_PAGE_SIZE}
     */
    List<StockMovement> getMovements(Long productId, MovementReason reason, int page, int size);

    /**
     * Like {@link #getMovements} for one product.
     *
     * @throws com.example.inventix.exception.ProductNotFoundException if the product doesn't exist
     */
    List<StockMovement> getMovementsForProduct(Long productId, int page, int size);
}
