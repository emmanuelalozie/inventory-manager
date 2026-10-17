package com.example.inventix.service.impl;

import com.example.inventix.dto.ProductionRunRequest;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.model.ProductionRun;
import com.example.inventix.model.ProductionRunStatus;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.ProductionRunRepository;
import com.example.inventix.service.Paging;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.ProductionRunService;
import com.example.inventix.service.StockReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class ProductionRunServiceImpl implements ProductionRunService {

    private final ProductionRunRepository productionRunRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    @Autowired
    public ProductionRunServiceImpl(ProductionRunRepository productionRunRepository,
                                    ProductRepository productRepository,
                                    ProductService productService) {
        this.productionRunRepository = productionRunRepository;
        this.productRepository = productRepository;
        this.productService = productService;
    }

    @Override
    public Page<ProductionRun> searchProductionRuns(ProductionRunStatus status, Long productId,
                                                    Integer page, Integer size) {
        return productionRunRepository.search(status, productId,
                Paging.pageable(page, size, Sort.by(Sort.Direction.DESC, "id")));
    }

    @Override
    public ProductionRun getProductionRun(Long id) {
        return productionRunRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Production run not found with id: " + id));
    }

    @Override
    public ProductionRun createProductionRun(ProductionRunRequest request) {
        ProductionRun run = new ProductionRun();
        run.setStatus(ProductionRunStatus.PLANNED);
        apply(run, request);
        return productionRunRepository.save(run);
    }

    @Override
    public ProductionRun updateProductionRun(Long id, ProductionRunRequest request) {
        ProductionRun run = getProductionRun(id);
        if (request.version() != null && !request.version().equals(run.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(ProductionRun.class, id);
        }
        if (run.getStatus() != ProductionRunStatus.PLANNED) {
            throw new ConflictException("Production run " + id + " is " + run.getStatus()
                    + "; only PLANNED runs can be changed");
        }
        apply(run, request);
        return productionRunRepository.save(run);
    }

    @Override
    public ProductionRun changeStatus(Long id, ProductionRunStatus status) {
        ProductionRun run = getProductionRun(id);
        if (run.getStatus() == status) {
            return run;
        }
        if (!run.getStatus().canTransitionTo(status)) {
            throw new ConflictException("Cannot change production run " + id + " from " + run.getStatus()
                    + " to " + status);
        }
        if (status == ProductionRunStatus.COMPLETED) {
            String note = "Production run #" + id + (run.getNotes() == null ? "" : ": " + run.getNotes());
            productService.changeStock(run.getProduct().getId(), run.getQuantity(), MovementReason.PRODUCTION,
                    truncate(note), StockReference.productionRun(id));
            run.setCompletedAt(LocalDateTime.now());
        }
        run.setStatus(status);
        return productionRunRepository.save(run);
    }

    @Override
    public void deleteProductionRun(Long id) {
        ProductionRun run = getProductionRun(id);
        if (run.getStatus() == ProductionRunStatus.COMPLETED) {
            throw new ConflictException("Production run " + id
                    + " is COMPLETED and is kept as a record; only PLANNED or CANCELLED runs can be deleted");
        }
        productionRunRepository.delete(run);
    }

    @Override
    public PlannedRun planIfNone(Long productId, int quantity) {
        return productionRunRepository.findFirstByProductIdAndStatusOrderByIdDesc(productId, ProductionRunStatus.PLANNED)
                .map(run -> new PlannedRun(run, false))
                .orElseGet(() -> new PlannedRun(
                        createProductionRun(new ProductionRunRequest(productId, quantity, null, null)), true));
    }

    // Loads the product (not a proxy), so the response can show its name after the transaction.
    private void apply(ProductionRun run, ProductionRunRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + request.productId()));
        run.setProduct(product);
        run.setQuantity(request.quantity());
        run.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().strip());
    }

    // Movement notes are limited to 500 characters; run notes can be longer.
    private static String truncate(String note) {
        return note.length() <= 500 ? note : note.substring(0, 497) + "...";
    }
}
