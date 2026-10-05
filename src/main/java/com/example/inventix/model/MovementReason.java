package com.example.inventix.model;

/**
 * Why a product's stock changed. Stored as a string in stock_movements.reason.
 */
public enum MovementReason {
    /** Stock taken by an order item (added, or its quantity raised). */
    SALE,
    /** Stock given back by an order (cancelled or deleted, item removed, or its quantity lowered). */
    CANCEL,
    /** Stock received from a supplier. */
    RECEIPT,
    /** Stock made in-house. */
    PRODUCTION,
    /** Manual correction or initial stock; always has a note. */
    ADJUSTMENT
}
