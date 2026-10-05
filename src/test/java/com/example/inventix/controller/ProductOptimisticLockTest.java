package com.example.inventix.controller;

import com.example.inventix.model.Product;
import com.example.inventix.service.ProductService;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Optimistic locking on products (O-8): the version is exposed in JSON, accepted on PUT,
 * and lock conflicts are returned by GlobalExceptionHandler as 409.
 */
@WebMvcTest(ProductController.class)
class ProductOptimisticLockTest {

    private static final String PRODUCT_JSON_WITH_VERSION =
            "{\"name\":\"Sample Product\",\"sku\":\"SKU12345\",\"description\":\"Description\","
                    + "\"price\":99.99,\"quantity\":100,\"version\":3}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    private static Product productWithVersion(long version) {
        Product product = new Product(1L, "Sample Product", "SKU12345", "Description",
                BigDecimal.valueOf(99.99), 100, null, null);
        product.setVersion(version);
        return product;
    }

    @Test
    void productJsonIncludesVersion() throws Exception {
        when(productService.getProductById(1L)).thenReturn(productWithVersion(4L));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void updatePassesClientVersionToService() throws Exception {
        when(productService.updateProduct(eq(1L), any(Product.class))).thenReturn(productWithVersion(4L));

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT_JSON_WITH_VERSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productService).updateProduct(eq(1L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3L);
    }

    @Test
    void staleVersionOnUpdateReturns409() throws Exception {
        when(productService.updateProduct(eq(1L), any(Product.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, 1L));

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PRODUCT_JSON_WITH_VERSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("changed by another request")))
                .andExpect(jsonPath("$.path").value("/api/products/1"));
    }

    @Test
    void jpaOptimisticLockExceptionReturns409() throws Exception {
        when(productService.adjustStock(eq(1L), anyInt())).thenThrow(new OptimisticLockException("stale"));

        mockMvc.perform(patch("/api/products/1/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("changed by another request")));
    }
}
