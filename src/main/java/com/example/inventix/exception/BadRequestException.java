package com.example.inventix.exception;

/**
 * A request the API rejects with 400 and a message that is safe to show to the client.
 * Other IllegalArgumentExceptions (e.g. from libraries) get a generic message instead.
 */
public class BadRequestException extends IllegalArgumentException {
    public BadRequestException(String message) {
        super(message);
    }
}
