package com.example.inventix.service;

import com.example.inventix.model.Supplier;

import java.util.List;

public interface SupplierService {

    /** All suppliers, by name. */
    List<Supplier> getAllSuppliers();

    /**
     * @throws com.example.inventix.exception.NotFoundException if there is no supplier with this id
     */
    Supplier getSupplier(Long id);

    /**
     * @throws com.example.inventix.exception.ConflictException if another supplier has the same name (ignoring case)
     */
    Supplier createSupplier(Supplier supplier);

    /**
     * Replaces name, email, phone and notes. A version that doesn't match the stored one is rejected with 409.
     */
    Supplier updateSupplier(Long id, Supplier supplier);

    /**
     * @throws com.example.inventix.exception.ConflictException if products or purchase orders use the supplier
     */
    void deleteSupplier(Long id);
}
