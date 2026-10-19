package com.example.inventix.dto;

import com.example.inventix.model.Customer;

import java.time.LocalDateTime;
import java.util.List;

public record CustomerResponse(
        Long id,
        String name,
        String email,
        String phone,
        String address,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getEmail(), customer.getPhone(),
                customer.getAddress(), customer.getNotes(), customer.getCreatedAt(), customer.getUpdatedAt(),
                customer.getVersion());
    }

    public static List<CustomerResponse> fromAll(List<Customer> customers) {
        return customers.stream().map(CustomerResponse::from).toList();
    }
}
