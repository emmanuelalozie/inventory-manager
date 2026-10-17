package com.example.inventix.service.impl;

import com.example.inventix.exception.BadRequestException;
import com.example.inventix.model.Category;
import com.example.inventix.model.MovementReason;
import com.example.inventix.repository.CategoryRepository;
import com.example.inventix.repository.OrderItemRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.repository.StockMovementRepository;
import com.example.inventix.service.DateRange;
import com.example.inventix.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReportServiceImpl implements ReportService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OrderItemRepository orderItemRepository;

    @Autowired
    public ReportServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository,
                             StockMovementRepository stockMovementRepository, OrderItemRepository orderItemRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    public StockValue stockValue() {
        Map<Long, String> names = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        List<CategoryValue> categories = productRepository.stockValueByCategory().stream()
                .map(row -> new CategoryValue((Long) row[0], row[0] == null ? null : names.get((Long) row[0]),
                        asLong(row[1]), asLong(row[2]), money(row[3]), money(row[4]), asLong(row[5])))
                // Named categories by name, then the uncategorised products last.
                .sorted(Comparator.comparing(CategoryValue::categoryName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        Totals total = new Totals(
                categories.stream().mapToLong(CategoryValue::products).sum(),
                categories.stream().mapToLong(CategoryValue::units).sum(),
                categories.stream().map(CategoryValue::valueAtPrice).reduce(BigDecimal.ZERO, BigDecimal::add),
                categories.stream().map(CategoryValue::valueAtCost).reduce(BigDecimal.ZERO, BigDecimal::add),
                categories.stream().mapToLong(CategoryValue::productsWithoutCost).sum());
        return new StockValue(total, categories);
    }

    @Override
    public List<MovementSummary> movementSummary(DateRange range) {
        return stockMovementRepository.summarizeByReason(range.start(), range.endExclusive()).stream()
                .map(row -> new MovementSummary((MovementReason) row[0], asLong(row[1]), asLong(row[2]), asLong(row[3])))
                .sorted(Comparator.comparing(MovementSummary::reason))
                .toList();
    }

    @Override
    public List<TopSeller> topSellers(DateRange range, int limit) {
        if (limit < 1 || limit > MAX_TOP_SELLERS) {
            throw new BadRequestException("limit must be between 1 and " + MAX_TOP_SELLERS);
        }
        return orderItemRepository.topSellers(range.start(), range.endExclusive(), PageRequest.of(0, limit)).stream()
                .map(row -> new TopSeller((Long) row[0], (String) row[1], (String) row[2], asLong(row[3]), money(row[4])))
                .toList();
    }

    private static long asLong(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static BigDecimal money(Object value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO
                : value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
