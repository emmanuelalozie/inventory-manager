package com.example.inventix.service;

/**
 * What caused a stock change, stored on its stock movement. At most one id is set.
 */
public record StockReference(Long orderId, Long purchaseOrderId, Long productionRunId) {

    public static final StockReference NONE = new StockReference(null, null, null);

    public static StockReference order(Long orderId) {
        return new StockReference(orderId, null, null);
    }

    public static StockReference purchaseOrder(Long purchaseOrderId) {
        return new StockReference(null, purchaseOrderId, null);
    }

    public static StockReference productionRun(Long productionRunId) {
        return new StockReference(null, null, productionRunId);
    }
}
