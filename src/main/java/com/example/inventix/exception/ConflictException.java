package com.example.inventix.exception;

/**
 * The request is valid but clashes with the current state (409), e.g. receiving more than was ordered or
 * deleting a supplier that is still in use. The message is shown to the client.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
