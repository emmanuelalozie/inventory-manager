package com.example.inventix.exception;

/**
 * Thrown when a product update tries to change the quantity. Stock only changes through
 * PATCH /api/products/{id}/stock (or orders), so every change is recorded in the stock ledger.
 */
public class QuantityChangeNotAllowedException extends RuntimeException {
    public QuantityChangeNotAllowedException(String message) {
        super(message);
    }
}
