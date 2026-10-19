package com.example.inventix.service.impl;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Customer;
import com.example.inventix.repository.CustomerRepository;
import com.example.inventix.repository.OrderRepository;
import com.example.inventix.service.CustomerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    @Autowired
    public CustomerServiceImpl(CustomerRepository customerRepository, OrderRepository orderRepository) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public List<Customer> getCustomers(String q) {
        if (q == null || q.isBlank()) {
            return customerRepository.findAllByOrderByNameAscIdAsc();
        }
        // Makes % and _ in the search text match literally (the query uses '\' as the LIKE escape character).
        String escaped = q.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return customerRepository.search("%" + escaped + "%");
    }

    @Override
    public Customer getCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found with id: " + id));
    }

    @Override
    public Customer createCustomer(Customer customer) {
        customer.setId(null);
        customer.setVersion(null);
        customer.setName(customer.getName().strip());
        return customerRepository.save(customer);
    }

    @Override
    public Customer updateCustomer(Long id, Customer details) {
        Customer existing = getCustomer(id);
        if (details.getVersion() != null && !details.getVersion().equals(existing.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }
        existing.setName(details.getName().strip());
        existing.setEmail(details.getEmail());
        existing.setPhone(details.getPhone());
        existing.setAddress(details.getAddress());
        existing.setNotes(details.getNotes());
        return customerRepository.save(existing);
    }

    @Override
    public void deleteCustomer(Long id) {
        Customer customer = getCustomer(id);
        if (orderRepository.existsByCustomerId(id)) {
            throw new ConflictException("Customer '" + customer.getName()
                    + "' has orders, which are kept as a record, so it can't be deleted.");
        }
        customerRepository.delete(customer);
    }
}
