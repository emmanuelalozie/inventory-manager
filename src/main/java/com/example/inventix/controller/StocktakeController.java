package com.example.inventix.controller;

import com.example.inventix.dto.StocktakeRequest;
import com.example.inventix.dto.StocktakeResponse;
import com.example.inventix.service.StocktakeService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocktake")
public class StocktakeController {

    private final StocktakeService stocktakeService;

    @Autowired
    public StocktakeController(StocktakeService stocktakeService) {
        this.stocktakeService = stocktakeService;
    }

    /** Books the differences between the counted and the recorded stock as ADJUSTMENT movements. */
    @PostMapping
    public StocktakeResponse applyCount(@Valid @RequestBody StocktakeRequest request) {
        return stocktakeService.applyCount(request);
    }
}
