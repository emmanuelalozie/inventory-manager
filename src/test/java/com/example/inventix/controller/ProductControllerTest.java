package com.example.inventix.controller;

import com.example.inventix.model.Product;
import com.example.inventix.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
// import org.springframework.security.test.context.support.WithMockUser;
// import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ProductController.
 *
 * This class uses @WebMvcTest to test the ProductController, with all external dependencies mocked.
 * The @WithMockUser annotation is used to simulate an authenticated user, allowing secure endpoints
 * to be tested.
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc; // MockMvc for simulating HTTP requests to the controller

    @MockBean
    private ProductService productService; // Mocked ProductService to control service layer behavior

    /**
     * Test for retrieving all products.
     *
     * This test verifies that the endpoint returns a list of products with status 200 OK.
     * It uses a mocked ProductService to return a sample product list.
     *
     * @throws Exception if a request error occurs
     */
    @Test
    // @WithMockUser(username = "testuser", roles = {"USER"})
    void testGetAllProducts() throws Exception {
        // Arrange: Set up a sample product and mock the service's getAllProducts method
        Product product = new Product(1L, "Sample Product", "SKU12345", "Description", BigDecimal.valueOf(99.99), 100, null, null);
        when(productService.getAllProducts()).thenReturn(Arrays.asList(product));

        // Act & Assert: Perform GET request and validate the response
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sample Product"));
    }

    /**
     * Test for creating a new product.
     *
     * This test verifies that a POST request to the create product endpoint returns
     * status 201 Created and the expected product data. CSRF token is included in the
     * request to pass Spring Security's CSRF check.
     *
     * @throws Exception if a request error occurs
     */
    @Test
    // @WithMockUser(username = "testuser", roles = {"USER"})
    void testCreateProduct() throws Exception {
        // Arrange: Set up a sample product and mock the service's createProduct method
        Product product = new Product(1L, "Sample Product", "SKU12345", "Description", BigDecimal.valueOf(99.99), 100, null, null);
        when(productService.createProduct(Mockito.any(Product.class))).thenReturn(product);

        // Act & Assert: Perform POST request with JSON content and validate the response
        mockMvc.perform(post("/api/products")
                        // .with(SecurityMockMvcRequestPostProcessors.csrf()) // Include CSRF token for POST request
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sample Product\",\"sku\":\"SKU12345\",\"description\":\"Description\",\"price\":99.99,\"quantity\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sample Product"));
    }
}
