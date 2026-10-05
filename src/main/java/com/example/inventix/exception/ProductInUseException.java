package com.example.inventix.exception;

public class ProductInUseException extends RuntimeException {
    public ProductInUseException(String message) {
        super(message);
    }
}
