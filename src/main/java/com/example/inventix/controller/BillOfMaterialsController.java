package com.example.inventix.controller;

import com.example.inventix.dto.ComponentResponse;
import com.example.inventix.dto.ComponentsRequest;
import com.example.inventix.service.BillOfMaterialsService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A made product's bill of materials. Completing a production run uses these components up.
 */
@RestController
public class BillOfMaterialsController {

    private final BillOfMaterialsService billOfMaterialsService;

    @Autowired
    public BillOfMaterialsController(BillOfMaterialsService billOfMaterialsService) {
        this.billOfMaterialsService = billOfMaterialsService;
    }

    @GetMapping("/api/products/{id}/components")
    public List<ComponentResponse> getComponents(@PathVariable Long id) {
        return ComponentResponse.fromAll(billOfMaterialsService.getComponents(id));
    }

    @PutMapping("/api/products/{id}/components")
    public List<ComponentResponse> replaceComponents(@PathVariable Long id, @Valid @RequestBody ComponentsRequest request) {
        return ComponentResponse.fromAll(billOfMaterialsService.replaceComponents(id, request.components()));
    }
}
