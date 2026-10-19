package com.example.inventix.service;

import com.example.inventix.dto.StocktakeRequest;
import com.example.inventix.dto.StocktakeResponse;

public interface StocktakeService {

    /**
     * Sets each counted product's stock to what was counted, writing an ADJUSTMENT movement with the note for each
     * difference. Products whose count matches are left alone. All lines are checked before anything is written,
     * so one bad line changes nothing.
     *
     * @throws com.example.inventix.exception.BadRequestException if a product is counted twice
     * @throws com.example.inventix.exception.ProductNotFoundException if a product doesn't exist
     * @throws com.example.inventix.exception.ConflictException if a product's stock no longer matches its
     *                                                         expectedQuantity
     */
    StocktakeResponse applyCount(StocktakeRequest request);
}
