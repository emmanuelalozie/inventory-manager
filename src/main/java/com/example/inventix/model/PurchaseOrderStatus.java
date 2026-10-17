package com.example.inventix.model;

/**
 * DRAFT (editable) -> ORDERED -> PARTIALLY_RECEIVED -> RECEIVED. RECEIVED is set automatically when every line is
 * in full. A partly received order can be CLOSED when the rest won't come; DRAFT and ORDERED (nothing received
 * yet) can be CANCELLED. Cancelling what is left on single lines settles the order the same way: CLOSED if
 * something arrived, CANCELLED if nothing did. RECEIVED, CLOSED and CANCELLED are final.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    ORDERED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    /** Transitions a client can request; receiving moves ORDERED/PARTIALLY_RECEIVED on by itself. */
    public boolean canTransitionTo(PurchaseOrderStatus next) {
        return switch (this) {
            case DRAFT -> next == ORDERED || next == CANCELLED;
            case ORDERED -> next == CANCELLED;
            case PARTIALLY_RECEIVED -> next == CLOSED;
            case RECEIVED, CLOSED, CANCELLED -> false;
        };
    }

    /** Ordered stock that may still arrive. */
    public boolean isOpen() {
        return this == DRAFT || this == ORDERED || this == PARTIALLY_RECEIVED;
    }
}
