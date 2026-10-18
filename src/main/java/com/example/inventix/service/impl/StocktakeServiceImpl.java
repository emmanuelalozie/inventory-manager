package com.example.inventix.service.impl;

import com.example.inventix.dto.StocktakeRequest;
import com.example.inventix.dto.StocktakeResponse;
import com.example.inventix.exception.BadRequestException;
import com.example.inventix.exception.ConflictException;
import com.example.inventix.model.MovementReason;
import com.example.inventix.model.Product;
import com.example.inventix.service.ProductService;
import com.example.inventix.service.StockReference;
import com.example.inventix.service.StocktakeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class StocktakeServiceImpl implements StocktakeService {

    static final String NOTE_PREFIX = "Stocktake: ";

    private final ProductService productService;

    @Autowired
    public StocktakeServiceImpl(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public StocktakeResponse applyCount(StocktakeRequest request) {
        Set<Long> seen = new HashSet<>();
        List<Product> products = new ArrayList<>();
        List<String> changedSince = new ArrayList<>();
        for (StocktakeRequest.Line line : request.lines()) {
            if (!seen.add(line.productId())) {
                throw new BadRequestException("Product " + line.productId() + " is counted more than once");
            }
            Product product = productService.getProductById(line.productId());
            if (line.expectedQuantity() != null && !line.expectedQuantity().equals(stockOf(product))) {
                changedSince.add(product.getName() + " (was " + line.expectedQuantity()
                        + ", now " + stockOf(product) + ")");
            }
            products.add(product);
        }
        if (!changedSince.isEmpty()) {
            throw new ConflictException("Stock changed since the count sheet was loaded for "
                    + String.join(", ", changedSince) + ". Reload the sheet, check those products again and resubmit.");
        }

        String note = NOTE_PREFIX + request.note().strip();
        List<StocktakeResponse.Change> changes = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            Product product = products.get(i);
            int before = stockOf(product);
            int after = request.lines().get(i).countedQuantity();
            if (after == before) {
                continue;
            }
            productService.changeStock(product.getId(), after - before, MovementReason.ADJUSTMENT, note,
                    StockReference.NONE);
            changes.add(new StocktakeResponse.Change(product.getId(), product.getSku(), product.getName(), before,
                    after, after - before));
        }
        return new StocktakeResponse(products.size(), changes);
    }

    private static int stockOf(Product product) {
        return product.getQuantity() == null ? 0 : product.getQuantity();
    }
}
