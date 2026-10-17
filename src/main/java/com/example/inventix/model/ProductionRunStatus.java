package com.example.inventix.model;

/**
 * PLANNED (editable) -> COMPLETED (stock added) or CANCELLED. COMPLETED and CANCELLED are final.
 */
public enum ProductionRunStatus {
    PLANNED,
    COMPLETED,
    CANCELLED;

    public boolean canTransitionTo(ProductionRunStatus next) {
        return this == PLANNED && (next == COMPLETED || next == CANCELLED);
    }
}
