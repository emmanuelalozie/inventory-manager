package com.example.inventix.controller;

import com.example.inventix.config.WebConfig;
import com.example.inventix.exception.DuplicateSkuException;
import com.example.inventix.exception.InsufficientStockException;
import com.example.inventix.exception.ProductInUseException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.Product;
import com.example.inventix.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for ProductController.
 * <p>
 * This class uses @WebMvcTest to test the ProductController, with the service layer mocked.
 * GlobalExceptionHandler and WebConfig (CORS) are part of the MVC slice, so error responses
 * and CORS headers are tested here too.
 */
@WebMvcTest(ProductController.class)
@Import(WebConfig.class) // already picked up as a WebMvcConfigurer; imported so the CORS tests never depend on that
class ProductControllerTest {

    private static final String VALID_PRODUCT_JSON =
            "{\"name\":\"Sample Product\",\"sku\":\"SKU12345\",\"description\":\"Description\",\"price\":99.99,\"quantity\":100}";

    @Autowired
    private MockMvc mockMvc; // MockMvc for simulating HTTP requests to the controller

    @MockBean
    private ProductService productService; // Mocked ProductService to control service layer behavior

    private static Product sampleProduct() {
        return new Product(1L, "Sample Product", "SKU12345", "Description", BigDecimal.valueOf(99.99), 100, null, null);
    }

    @Test
    void testGetAllProducts() throws Exception {
        when(productService.getAllProducts()).thenReturn(List.of(sampleProduct()));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Sample Product"));
    }

    @Test
    void getProductById_returnsProduct() throws Exception {
        when(productService.getProductById(1L)).thenReturn(sampleProduct());

        mockMvc.perform(get("/api/products/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sku").value("SKU12345"));
    }

    @Test
    void getProductById_returns404WithErrorBody_whenProductIsMissing() throws Exception {
        when(productService.getProductById(99L)).thenThrow(new ProductNotFoundException("Product not found with id: 99"));

        mockMvc.perform(get("/api/products/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Product not found with id: 99"))
                .andExpect(jsonPath("$.path").value("/api/products/99"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void getProductById_returns400_whenIdIsNotANumber() throws Exception {
        mockMvc.perform(get("/api/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getLowStockProducts_usesDefaultThresholdOf10() throws Exception {
        when(productService.getLowStockProducts(10)).thenReturn(List.of(sampleProduct()));

        mockMvc.perform(get("/api/products/low-stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("SKU12345"));

        verify(productService).getLowStockProducts(10);
    }

    @Test
    void getLowStockProducts_usesThresholdParameter() throws Exception {
        when(productService.getLowStockProducts(3)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/low-stock").param("threshold", "3"))
                .andExpect(status().isOk());

        verify(productService).getLowStockProducts(3);
    }

    @Test
    void testCreateProduct() throws Exception {
        when(productService.createProduct(Mockito.any(Product.class))).thenReturn(sampleProduct());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/products/1")))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Sample Product"));
    }

    @Test
    void createProduct_returns400WithFieldErrors_whenBodyIsInvalid() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sku\":\"SKU-1\",\"price\":-1,\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("name", "price")));

        verify(productService, never()).createProduct(any(Product.class));
    }

    @Test
    void createProduct_returns400_whenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));

        verify(productService, never()).createProduct(any(Product.class));
    }

    @Test
    void createProduct_returns415_whenBodyIsNotJson() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void createProduct_returns409_whenSkuAlreadyExists() throws Exception {
        when(productService.createProduct(any(Product.class)))
                .thenThrow(new DuplicateSkuException("A product with SKU 'SKU12345' already exists"));

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("SKU12345")));
    }

    @Test
    void updateProduct_returnsUpdatedProduct() throws Exception {
        Product updated = sampleProduct();
        updated.setName("Renamed");
        when(productService.updateProduct(eq(1L), any(Product.class))).thenReturn(updated);

        mockMvc.perform(put("/api/products/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON.replace("Sample Product", "Renamed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void updateProduct_returns404_whenProductIsMissing() throws Exception {
        when(productService.updateProduct(eq(99L), any(Product.class)))
                .thenThrow(new ProductNotFoundException("Product not found with id: 99"));

        mockMvc.perform(put("/api/products/{id}", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void adjustStock_returnsProductWithNewQuantity() throws Exception {
        Product adjusted = sampleProduct();
        adjusted.setQuantity(95);
        when(productService.adjustStock(1L, -5)).thenReturn(adjusted);

        mockMvc.perform(patch("/api/products/{id}/stock", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(95));
    }

    @Test
    void adjustStock_returns409_whenStockWouldGoNegative() throws Exception {
        when(productService.adjustStock(1L, -500))
                .thenThrow(new InsufficientStockException("Not enough stock for product 'Sample Product'"));

        mockMvc.perform(patch("/api/products/{id}/stock", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":-500}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void adjustStock_returns400_whenDeltaIsMissing() throws Exception {
        mockMvc.perform(patch("/api/products/{id}/stock", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("delta"));

        verify(productService, never()).adjustStock(anyLong(), anyInt());
    }

    @Test
    void deleteProduct_returns204() throws Exception {
        mockMvc.perform(delete("/api/products/{id}", 1L))
                .andExpect(status().isNoContent());

        verify(productService).deleteProduct(1L);
    }

    @Test
    void deleteProduct_returns404_whenProductIsMissing() throws Exception {
        doThrow(new ProductNotFoundException("Product not found with id: 99")).when(productService).deleteProduct(99L);

        mockMvc.perform(delete("/api/products/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 99"));
    }

    @Test
    void deleteProduct_returns409_whenProductIsUsedByAnOrder() throws Exception {
        doThrow(new ProductInUseException("Product 1 is used by existing orders and cannot be deleted"))
                .when(productService).deleteProduct(1L);

        mockMvc.perform(delete("/api/products/{id}", 1L))
                .andExpect(status().isConflict());
    }

    @Test
    void corsPreflight_allowsTauriOrigin() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header(HttpHeaders.ORIGIN, "http://tauri.localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://tauri.localhost"));
    }

    @Test
    void corsPreflight_rejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/products")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
