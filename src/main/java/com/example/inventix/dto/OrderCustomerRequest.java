package com.example.inventix.dto;

/**
 * Request body for PATCH /api/orders/{id}/customer: {"customerId": 3}, or {"customerId": null} for no customer.
 */
public record OrderCustomerRequest(Long customerId) {
}
