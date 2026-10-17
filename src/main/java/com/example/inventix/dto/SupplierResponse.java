package com.example.inventix.dto;

import com.example.inventix.model.Supplier;

import java.time.LocalDateTime;
import java.util.List;

public record SupplierResponse(
        Long id,
        String name,
        String email,
        String phone,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(supplier.getId(), supplier.getName(), supplier.getEmail(), supplier.getPhone(),
                supplier.getNotes(), supplier.getCreatedAt(), supplier.getUpdatedAt(), supplier.getVersion());
    }

    public static List<SupplierResponse> fromAll(List<Supplier> suppliers) {
        return suppliers.stream().map(SupplierResponse::from).toList();
    }
}
