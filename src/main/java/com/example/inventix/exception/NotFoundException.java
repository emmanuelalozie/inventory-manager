package com.example.inventix.exception;

/**
 * A requested record doesn't exist (404). Used by the suppliers, purchase orders and production runs.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
