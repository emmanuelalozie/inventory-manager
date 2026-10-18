package com.example.inventix.controller;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Customer;
import com.example.inventix.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    private static Customer customer(long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setAddress("1 Main St");
        customer.setVersion(0L);
        return customer;
    }

    @Test
    void listsCustomers_passingTheSearchText() throws Exception {
        when(customerService.getCustomers("ann")).thenReturn(List.of(customer(1L, "Ann Lee")));

        mockMvc.perform(get("/api/customers").param("q", "ann"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Ann Lee"))
                .andExpect(jsonPath("$[0].address").value("1 Main St"))
                .andExpect(jsonPath("$[0].version").value(0));
    }

    @Test
    void createReturns201WithLocation_andBlankOptionalFieldsBecomeNull() throws Exception {
        when(customerService.createCustomer(any())).thenReturn(customer(3L, "Ann Lee"));

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ann Lee\",\"email\":\"\",\"phone\":\" \",\"address\":\" 1 Main St \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/customers/3")));

        verify(customerService).createCustomer(argThat(c -> c.getEmail() == null && c.getPhone() == null
                && "1 Main St".equals(c.getAddress())));
    }

    @Test
    void createReturns400_forMissingNameOrBadEmail() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));

        verify(customerService, never()).createCustomer(any());
    }

    @Test
    void updatePassesTheVersion() throws Exception {
        when(customerService.updateCustomer(eq(1L), any())).thenReturn(customer(1L, "Ann Lee"));

        mockMvc.perform(put("/api/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ann Lee\",\"version\":4}"))
                .andExpect(status().isOk());

        verify(customerService).updateCustomer(eq(1L), argThat(c -> Long.valueOf(4L).equals(c.getVersion())));
    }

    @Test
    void getReturns404_forAnUnknownCustomer() throws Exception {
        when(customerService.getCustomer(9L)).thenThrow(new NotFoundException("Customer not found with id: 9"));

        mockMvc.perform(get("/api/customers/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Customer not found with id: 9"));
    }

    @Test
    void deleteReturns204_or409WhenTheCustomerHasOrders() throws Exception {
        mockMvc.perform(delete("/api/customers/2")).andExpect(status().isNoContent());

        doThrow(new ConflictException("Customer 'Ann Lee' has orders, which are kept as a record, so it can't be deleted."))
                .when(customerService).deleteCustomer(1L);
        mockMvc.perform(delete("/api/customers/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(endsWith("can't be deleted.")));
    }
}
