package com.example.inventix.repository;

import com.example.inventix.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findAllByOrderByNameAscIdAsc();

    /** Customers whose name, email or phone matches the lower-case LIKE pattern ('\' escapes), by name. */
    @Query("select c from Customer c where lower(c.name) like :pattern escape '\\'"
            + " or lower(c.email) like :pattern escape '\\'"
            + " or lower(c.phone) like :pattern escape '\\'"
            + " order by c.name asc, c.id asc")
    List<Customer> search(@Param("pattern") String pattern);
}
