package com.example.inventix.model;

public enum OrderStatus {
    PENDING,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    /**
     * Allowed transitions: PENDING -> SHIPPED or CANCELLED, SHIPPED -> DELIVERED.
     * DELIVERED and CANCELLED are final.
     */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == SHIPPED || next == CANCELLED;
            case SHIPPED -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
