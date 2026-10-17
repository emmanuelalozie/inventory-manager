package com.example.inventix.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * Replaces a made product's bill of materials: {"components": [{"componentId": 7, "quantity": 4}]}.
 * An empty list removes all components.
 */
public record ComponentsRequest(
        @NotNull(message = "components is required")
        List<@NotNull(message = "components must not contain null") @Valid Line> components) {

    public record Line(
            @NotNull(message = "componentId is required")
            Long componentId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than 0")
            Integer quantity) {
    }
}
