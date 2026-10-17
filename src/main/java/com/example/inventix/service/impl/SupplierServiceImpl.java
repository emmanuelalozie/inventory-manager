package com.example.inventix.service.impl;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Supplier;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.PurchaseOrderRepository;
import com.example.inventix.repository.SupplierRepository;
import com.example.inventix.service.SupplierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    public SupplierServiceImpl(SupplierRepository supplierRepository, ProductRepository productRepository,
                               PurchaseOrderRepository purchaseOrderRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    @Override
    public List<Supplier> getAllSuppliers() {
        return supplierRepository.findAllByOrderByNameAsc();
    }

    @Override
    public Supplier getSupplier(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Supplier not found with id: " + id));
    }

    @Override
    public Supplier createSupplier(Supplier supplier) {
        supplier.setId(null);
        supplier.setVersion(null);
        supplier.setName(supplier.getName().strip());
        if (supplierRepository.existsByNameIgnoreCase(supplier.getName())) {
            throw new ConflictException("A supplier named '" + supplier.getName() + "' already exists");
        }
        return supplierRepository.save(supplier);
    }

    @Override
    public Supplier updateSupplier(Long id, Supplier details) {
        Supplier existing = getSupplier(id);
        if (details.getVersion() != null && !details.getVersion().equals(existing.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Supplier.class, id);
        }
        String name = details.getName().strip();
        if (supplierRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ConflictException("A supplier named '" + name + "' already exists");
        }
        existing.setName(name);
        existing.setEmail(details.getEmail());
        existing.setPhone(details.getPhone());
        existing.setNotes(details.getNotes());
        return supplierRepository.save(existing);
    }

    @Override
    public void deleteSupplier(Long id) {
        Supplier supplier = getSupplier(id);
        if (productRepository.existsBySupplierId(id)) {
            throw new ConflictException("Supplier '" + supplier.getName()
                    + "' is set as the supplier of products. Change those products first.");
        }
        if (purchaseOrderRepository.existsBySupplierId(id)) {
            throw new ConflictException("Supplier '" + supplier.getName()
                    + "' has purchase orders, which are kept as a record, so it can't be deleted.");
        }
        supplierRepository.delete(supplier);
    }
}
