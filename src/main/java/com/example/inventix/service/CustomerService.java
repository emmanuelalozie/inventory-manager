package com.example.inventix.service;

import com.example.inventix.model.Customer;

import java.util.List;

public interface CustomerService {

    /** Customers by name; with q, only those whose name, email or phone contains it (ignoring case). */
    List<Customer> getCustomers(String q);

    /**
     * @throws com.example.inventix.exception.NotFoundException if there is no customer with this id
     */
    Customer getCustomer(Long id);

    Customer createCustomer(Customer customer);

    /**
     * Replaces name, email, phone, address and notes. A version that doesn't match the stored one is rejected
     * with 409.
     */
    Customer updateCustomer(Long id, Customer customer);

    /**
     * @throws com.example.inventix.exception.ConflictException if orders are for this customer
     */
    void deleteCustomer(Long id);
}
