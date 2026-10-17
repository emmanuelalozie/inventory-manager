package com.example.inventix.service;

import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;
import org.springframework.data.domain.Page;

public interface ProductionRunService {

    /** Runs newest first, optionally filtered. Without page and size every match is returned. */
    Page<ProductionRun> searchProductionRuns(ProductionRunStatus status, Long productId, Integer page, Integer size);

    /**
     * @throws com.example.inventix.exception.NotFoundException if there is no run with this id
     */
    ProductionRun getProductionRun(Long id);

    /** Plans a run (status PLANNED). Stock doesn't change until it is completed. */
    ProductionRun createProductionRun(ProductionRunRequest request);

    /**
     * Changes a PLANNED run's product, quantity and notes.
     *
     * @throws com.example.inventix.exception.ConflictException if the run isn't PLANNED
     */
    ProductionRun updateProductionRun(Long id, ProductionRunRequest request);

    /**
     * PLANNED -> COMPLETED uses up the product's components (quantity per unit x run quantity, CONSUMPTION
     * movements) and adds the quantity to stock with a PRODUCTION movement; PLANNED -> CANCELLED changes nothing
     * else. If a component is short, nothing changes and InsufficientStockException (409) says which.
     *
     * @throws com.example.inventix.exception.ConflictException for any other change
     */
    ProductionRun changeStatus(Long id, ProductionRunStatus status);

    /** Deletes a PLANNED or CANCELLED run. Completed runs are kept as a record. */
    void deleteProductionRun(Long id);

    /**
     * Returns the product's newest PLANNED run, or plans one for {@code quantity} if there is none.
     */
    PlannedRun planIfNone(Long productId, int quantity);

    record PlannedRun(ProductionRun run, boolean created) {
    }
}
