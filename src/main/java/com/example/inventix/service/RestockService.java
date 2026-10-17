package com.example.inventix.service;

import com.example.inventix.model.ProductSource;

import java.util.List;

/**
 * The reorder list (products at or below their reorder point) and the Restock action, which turns a product's
 * reorder quantity into a line on its supplier's draft purchase order (BUY) or a planned production run (MAKE).
 * Nothing is ordered or made until someone orders the draft or completes the run.
 */
public interface RestockService {

    /** Products with stock at or below their reorder point, lowest stock first. */
    List<ReorderItem> getReorderList();

    /**
     * Restocks one product, whatever its current stock.
     *
     * @throws com.example.inventix.exception.ConflictException if the product has no reorder quantity, or is
     *                                                         bought but has no supplier
     */
    RestockResult restock(Long productId);

    /**
     * Restocks every product on the reorder list that is set up for it and not already covered by stock on order.
     * Returns one result per product on the list; skipped products say why.
     */
    List<RestockResult> restockAll();

    /**
     * @param onOrder units still to arrive on DRAFT, ORDERED and PARTIALLY_RECEIVED purchase orders, plus units in
     *                PLANNED production runs
     * @param covered quantity + onOrder is above the reorder point, so nothing more is needed
     * @param problem why Restock can't act on this product yet, or null
     */
    record ReorderItem(Long productId, String name, String sku, ProductSource source, Long supplierId,
                       String supplierName, int quantity, int reorderPoint, Integer reorderQuantity, long onOrder,
                       boolean covered, String problem) {
    }

    /**
     * @param action          PURCHASE_ORDER, PRODUCTION_RUN or SKIPPED
     * @param created         a new draft purchase order or production run was started
     * @param added           the product was added (false when it was already on the draft or had a planned run)
     */
    record RestockResult(Long productId, String sku, String action, Long purchaseOrderId, Long productionRunId,
                         boolean created, boolean added, String message) {
    }
}
