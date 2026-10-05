package com.example.inventix.service.impl;

import com.example.inventix.exception.ProductNotFoundException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.StockMovement;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.StockMovementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class StockMovementServiceImpl implements StockMovementService {

    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;

    @Autowired
    public StockMovementServiceImpl(StockMovementRepository stockMovementRepository,
                                    ProductRepository productRepository) {
        this.stockMovementRepository = stockMovementRepository;
        this.productRepository = productRepository;
    }

    @Override
    public List<StockMovement> getMovements(Long productId, MovementReason reason, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // The query sorts newest first itself, so the page request carries no sort.
        return stockMovementRepository.search(productId, reason, PageRequest.of(page, size));
    }

    @Override
    public List<StockMovement> getMovementsForProduct(Long productId, int page, int size) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException("Product not found with id: " + productId);
        }
        return getMovements(productId, null, page, size);
    }
}
