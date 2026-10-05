package com.example.inventix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application (H2, JPA, Spring Batch, seed data) and runs one order through the real HTTP API.
 * Keep all full-context tests in this class: every @SpringBootTest context shares the same in-memory H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventixApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void contextLoads() {
    }

    @Test
    void orderLifecycle_overHttp_reservesAndRestoresStock() throws Exception {
        String productJson = mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"E2E Widget\",\"sku\":\"E2E-001\",\"description\":\"End-to-end test product\","
                                + "\"price\":12.50,\"quantity\":10}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int productId = objectMapper.readTree(productJson).get("id").asInt();

        String orderJson = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + productId + ",\"quantity\":4}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(50.0))
                .andReturn().getResponse().getContentAsString();
        int orderId = objectMapper.readTree(orderJson).get("id").asInt();

        // Reading the order in a new request loads the items lazily and must not recurse into the order.
        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderItems", hasSize(1)))
                .andExpect(jsonPath("$.orderItems[0].orderId").value(orderId))
                .andExpect(jsonPath("$.orderItems[0].product.id").value(productId))
                .andExpect(jsonPath("$.orderItems[0].subtotal").value(50.0))
                .andExpect(jsonPath("$.orderItems[0].order").doesNotExist());

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(6));

        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.quantity").value(10));

        // CANCELLED is final.
        mockMvc.perform(patch("/api/orders/{id}/status", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isConflict());

        // The product is still referenced by the cancelled order.
        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isConflict());
    }

    @Test
    void missingOrder_returns404ErrorBody() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/orders/999999"));
    }
}
