package com.example.inventix.dto;

import com.example.inventix.model.Customer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating or replacing a customer. version is optional on PUT and rejects stale edits.
 */
public record CustomerRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 255, message = "Name must be at most 255 characters")
        String name,

        @Email(message = "Email must be a valid email address")
        @Size(max = 255, message = "Email must be at most 255 characters")
        String email,

        @Size(max = 50, message = "Phone must be at most 50 characters")
        String phone,

        @Size(max = 1000, message = "Address must be at most 1000 characters")
        String address,

        @Size(max = 1000, message = "Notes must be at most 1000 characters")
        String notes,

        Long version) {

    public Customer toEntity() {
        Customer customer = new Customer();
        customer.setName(name);
        customer.setEmail(blankToNull(email));
        customer.setPhone(blankToNull(phone));
        customer.setAddress(blankToNull(address));
        customer.setNotes(blankToNull(notes));
        customer.setVersion(version);
        return customer;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
