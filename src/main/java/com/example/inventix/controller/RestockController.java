package com.example.inventix.controller;

import com.example.inventix.service.RestockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The reorder list and the Restock action. Restock only fills a draft purchase order or plans a production run;
 * nothing is ordered or made until that is reviewed.
 */
@RestController
public class RestockController {

    private final RestockService restockService;

    @Autowired
    public RestockController(RestockService restockService) {
        this.restockService = restockService;
    }

    @GetMapping("/api/reorder-list")
    public List<RestockService.ReorderItem> getReorderList() {
        return restockService.getReorderList();
    }

    @PostMapping("/api/reorder-list/restock")
    public List<RestockService.RestockResult> restockAll() {
        return restockService.restockAll();
    }

    @PostMapping("/api/products/{id}/restock")
    public RestockService.RestockResult restock(@PathVariable Long id) {
        return restockService.restock(id);
    }
}
