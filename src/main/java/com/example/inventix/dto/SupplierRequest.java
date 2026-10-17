package com.example.inventix.dto;

import com.example.inventix.model.Supplier;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or replacing a supplier. version is optional on PUT and rejects stale edits.
 */
public record SupplierRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @Email(message = "Email must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Size(max = 50, message = "Phone must be at most 50 characters")
        String phone,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes,

        Long version) {

    public Supplier toEntity() {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setEmail(blankToNull(email));
        supplier.setPhone(blankToNull(phone));
        supplier.setNotes(blankToNull(notes));
        supplier.setVersion(version);
        return supplier;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
