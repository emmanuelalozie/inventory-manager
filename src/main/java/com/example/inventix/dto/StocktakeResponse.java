package com.example.inventix.dto;

import java.util.List;

/**
 * Result of a stocktake: how many products were counted, and the ones whose stock changed.
 */
public record StocktakeResponse(int counted, List<Change> changes) {

    public record Change(Long productId, String productSku, String productName, int before, int after, int delta) {
    }
}
