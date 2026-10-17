package com.example.inventix.controller;

import com.example.inventix.config.WebConfig;
import com.example.inventix.dto.PurchaseOrderRequest;
import com.example.inventix.dto.PurchaseOrderResponse;
import com.example.inventix.dto.PurchaseOrderStatusRequest;
import com.example.inventix.dto.ReceiptRequest;
import com.example.inventix.model.PurchaseOrder;
import com.example.inventix.model.PurchaseOrderStatus;
import com.example.inventix.service.PurchaseOrderService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    @Autowired
    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    /** Newest first, optionally filtered by status and supplier. X-Total-Count holds the number of matches. */
    @GetMapping
    public ResponseEntity<List<PurchaseOrderResponse>> getPurchaseOrders(
            @RequestParam(required = false) PurchaseOrderStatus status,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<PurchaseOrder> result = purchaseOrderService.searchPurchaseOrders(status, supplierId, page, size);
        return ResponseEntity.ok()
                .header(WebConfig.TOTAL_COUNT_HEADER, String.valueOf(result.getTotalElements()))
                .body(PurchaseOrderResponse.fromAll(result.getContent()));
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse getPurchaseOrder(@PathVariable Long id) {
        return PurchaseOrderResponse.from(purchaseOrderService.getPurchaseOrder(id));
    }

    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(@Valid @RequestBody PurchaseOrderRequest request) {
        PurchaseOrderResponse created = PurchaseOrderResponse.from(purchaseOrderService.createPurchaseOrder(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public PurchaseOrderResponse updatePurchaseOrder(@PathVariable Long id,
                                                     @Valid @RequestBody PurchaseOrderRequest request) {
        return PurchaseOrderResponse.from(purchaseOrderService.updatePurchaseOrder(id, request));
    }

    @PatchMapping("/{id}/status")
    public PurchaseOrderResponse changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody PurchaseOrderStatusRequest request) {
        return PurchaseOrderResponse.from(purchaseOrderService.changeStatus(id, request.status()));
    }

    @PostMapping("/{id}/receipts")
    public PurchaseOrderResponse receive(@PathVariable Long id, @Valid @RequestBody ReceiptRequest request) {
        return PurchaseOrderResponse.from(purchaseOrderService.receive(id, request));
    }

    /** Cancels what is still to come on one line; the rest of the order is unchanged. */
    @PostMapping("/{id}/lines/{lineId}/cancel-remaining")
    public PurchaseOrderResponse cancelRemaining(@PathVariable Long id, @PathVariable Long lineId) {
        return PurchaseOrderResponse.from(purchaseOrderService.cancelRemaining(id, lineId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePurchaseOrder(@PathVariable Long id) {
        purchaseOrderService.deletePurchaseOrder(id);
        return ResponseEntity.noContent().build();
    }
}
