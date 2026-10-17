package com.example.inventix.controller;

import com.example.inventix.config.WebConfig;
import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.dto.ProductionRunResponse;
import com.example.inventix.dto.ProductionRunStatusRequest;
import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;
import com.example.inventix.service.ProductionRunService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/production-runs")
public class ProductionRunController {

    private final ProductionRunService productionRunService;

    @Autowired
    public ProductionRunController(ProductionRunService productionRunService) {
        this.productionRunService = productionRunService;
    }

    /** Newest first, optionally filtered by status and product. X-Total-Count holds the number of matches. */
    @GetMapping
    public ResponseEntity<List<ProductionRunResponse>> getProductionRuns(
            @RequestParam(required = false) ProductionRunStatus status,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<ProductionRun> result = productionRunService.searchProductionRuns(status, productId, page, size);
        return ResponseEntity.ok()
                .header(WebConfig.TOTAL_COUNT_HEADER, String.valueOf(result.getTotalElements()))
                .body(ProductionRunResponse.fromAll(result.getContent()));
    }

    @GetMapping("/{id}")
    public ProductionRunResponse getProductionRun(@PathVariable Long id) {
        return ProductionRunResponse.from(productionRunService.getProductionRun(id));
    }

    @PostMapping
    public ResponseEntity<ProductionRunResponse> createProductionRun(@Valid @RequestBody ProductionRunRequest request) {
        ProductionRunResponse created = ProductionRunResponse.from(productionRunService.createProductionRun(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ProductionRunResponse updateProductionRun(@PathVariable Long id,
                                                     @Valid @RequestBody ProductionRunRequest request) {
        return ProductionRunResponse.from(productionRunService.updateProductionRun(id, request));
    }

    @PatchMapping("/{id}/status")
    public ProductionRunResponse changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody ProductionRunStatusRequest request) {
        return ProductionRunResponse.from(productionRunService.changeStatus(id, request.status()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProductionRun(@PathVariable Long id) {
        productionRunService.deleteProductionRun(id);
        return ResponseEntity.noContent().build();
    }
}
