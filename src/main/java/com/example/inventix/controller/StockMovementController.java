package com.example.inventix.controller;

import com.example.inventix.dto.StockMovementResponse;
import com.example.inventix.model.MovementReason;
import com.example.inventix.service.StockMovementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only access to the stock ledger, newest first, one page at a time (page is 0-based).
 * Movements are written by the stock and order endpoints, never directly.
 */
@RestController
public class StockMovementController {

    static final String DEFAULT_PAGE_SIZE = "100";

    private final StockMovementService stockMovementService;

    @Autowired
    public StockMovementController(StockMovementService stockMovementService) {
        this.stockMovementService = stockMovementService;
    }

    @GetMapping("/api/stock-movements")
    public List<StockMovementResponse> getMovements(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementReason reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
        return StockMovementResponse.fromAll(stockMovementService.getMovements(productId, reason, page, size));
    }

    @GetMapping("/api/products/{id}/movements")
    public List<StockMovementResponse> getMovementsForProduct(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
        return StockMovementResponse.fromAll(stockMovementService.getMovementsForProduct(id, page, size));
    }
}
