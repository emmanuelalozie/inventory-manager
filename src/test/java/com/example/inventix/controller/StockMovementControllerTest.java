package com.example.inventix.controller;

import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.StockMovement;
import com.example.inventix.service.StockMovementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockMovementController.class)
class StockMovementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StockMovementService stockMovementService;

    private static StockMovement movement() {
        Product product = new Product(1L, "Mouse", "WM-001", null, new BigDecimal("24.99"), 7, null, null);
        StockMovement movement = new StockMovement(product, -3, MovementReason.SALE, "Added to order #4", 4L);
        movement.setId(10L);
        movement.setCreatedAt(LocalDateTime.of(2026, 10, 4, 15, 30));
        return movement;
    }

    @Test
    void getMovementsForProduct_returnsDtos() throws Exception {
        when(stockMovementService.getMovementsForProduct(1L, 0, 100)).thenReturn(List.of(movement()));

        mockMvc.perform(get("/api/products/{id}/movements", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].productId").value(1))
                .andExpect(jsonPath("$[0].productName").value("Mouse"))
                .andExpect(jsonPath("$[0].productSku").value("WM-001"))
                .andExpect(jsonPath("$[0].delta").value(-3))
                .andExpect(jsonPath("$[0].reason").value("SALE"))
                .andExpect(jsonPath("$[0].note").value("Added to order #4"))
                .andExpect(jsonPath("$[0].orderId").value(4))
                .andExpect(jsonPath("$[0].purchaseOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].locationId").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-04T15:30:00"))
                .andExpect(jsonPath("$[0].product").doesNotExist());
    }

    @Test
    void getMovementsForProduct_passesPaging() throws Exception {
        when(stockMovementService.getMovementsForProduct(1L, 2, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/{id}/movements", 1L).param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(stockMovementService).getMovementsForProduct(1L, 2, 20);
    }

    @Test
    void getMovementsForProduct_returns404_whenProductIsMissing() throws Exception {
        when(stockMovementService.getMovementsForProduct(99L, 0, 100))
                .thenThrow(new ProductNotFoundException("Product not found with id: 99"));

        mockMvc.perform(get("/api/products/{id}/movements", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product not found with id: 99"));
    }

    @Test
    void getMovements_withoutFilters_usesDefaults() throws Exception {
        when(stockMovementService.getMovements(null, null, 0, 100)).thenReturn(List.of(movement()));

        mockMvc.perform(get("/api/stock-movements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("SALE"));

        verify(stockMovementService).getMovements(null, null, 0, 100);
    }

    @Test
    void getMovements_passesProductAndReasonFilters() throws Exception {
        when(stockMovementService.getMovements(1L, MovementReason.ADJUSTMENT, 0, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/stock-movements")
                        .param("productId", "1")
                        .param("reason", "ADJUSTMENT")
                        .param("size", "50"))
                .andExpect(status().isOk());

        verify(stockMovementService).getMovements(1L, MovementReason.ADJUSTMENT, 0, 50);
    }

    @Test
    void getMovements_returns400_forUnknownReason() throws Exception {
        mockMvc.perform(get("/api/stock-movements").param("reason", "THEFT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'THEFT' for parameter 'reason'"));

        verify(stockMovementService, never()).getMovements(any(), any(), anyInt(), anyInt());
    }

    @Test
    void getMovements_returns400_forBadPageSize() throws Exception {
        when(stockMovementService.getMovements(null, null, 0, 1000))
                .thenThrow(new IllegalArgumentException("size must be between 1 and 500"));

        mockMvc.perform(get("/api/stock-movements").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be between 1 and 500"));
    }

    @Test
    void movements_areReadOnly() throws Exception {
        mockMvc.perform(post("/api/stock-movements"))
                .andExpect(status().isMethodNotAllowed());

        verify(stockMovementService, never()).getMovementsForProduct(anyLong(), anyInt(), anyInt());
    }
}
