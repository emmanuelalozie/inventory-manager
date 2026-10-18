package com.example.inventix.controller;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Supplier;
import com.example.inventix.service.SupplierService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupplierController.class)
class SupplierControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierService supplierService;

    private static Supplier supplier(long id, String name) {
        Supplier supplier = new Supplier();
        supplier.setId(id);
        supplier.setName(name);
        supplier.setVersion(0L);
        return supplier;
    }

    @Test
    void listsSuppliers() throws Exception {
        when(supplierService.getAllSuppliers()).thenReturn(List.of(supplier(1L, "Acme")));

        mockMvc.perform(get("/api/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Acme"))
                .andExpect(jsonPath("$[0].version").value(0));
    }

    @Test
    void createReturns201WithLocation_andBlankOptionalFieldsBecomeNull() throws Exception {
        when(supplierService.createSupplier(any())).thenReturn(supplier(3L, "Acme"));

        mockMvc.perform(post("/api/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Acme\",\"email\":\"\",\"phone\":\" \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/suppliers/3")));

        verify(supplierService).createSupplier(argThat(s -> s.getEmail() == null && s.getPhone() == null));
    }

    @Test
    void createReturns400_forMissingNameOrBadEmail() throws Exception {
        mockMvc.perform(post("/api/suppliers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));

        verify(supplierService, never()).createSupplier(any());
    }

    @Test
    void missingSupplierIs404_andSupplierInUseIs409() throws Exception {
        when(supplierService.getSupplier(9L)).thenThrow(new NotFoundException("Supplier not found with id: 9"));
        doThrow(new ConflictException("Supplier 'Acme' is set as the supplier of products."))
                .when(supplierService).deleteSupplier(1L);

        mockMvc.perform(get("/api/suppliers/9")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/suppliers/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Supplier 'Acme' is set as the supplier of products."));
    }
}
