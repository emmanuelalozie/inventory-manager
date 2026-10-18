package com.example.inventix.controller;

import com.example.inventix.config.WebConfig;
import com.example.inventix.dto.StockMovementResponse;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;
import com.example.inventix.service.DateRange;
import com.example.inventix.service.StockMovementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
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

    /**
     * from and to are dates (2026-10-01), both included. X-Total-Count holds the number of matches.
     */
    @GetMapping("/api/stock-movements")
    public ResponseEntity<List<StockMovementResponse>> getMovements(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementReason reason,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
        Page<StockMovement> result = stockMovementService.searchMovements(productId, reason, DateRange.of(from, to),
                page, size);
        return ResponseEntity.ok()
                .header(WebConfig.TOTAL_COUNT_HEADER, String.valueOf(result.getTotalElements()))
                .body(StockMovementResponse.fromAll(result.getContent()));
    }

    @GetMapping("/api/products/{id}/movements")
    public List<StockMovementResponse> getMovementsForProduct(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
        return StockMovementResponse.fromAll(stockMovementService.getMovementsForProduct(id, page, size));
    }
}
