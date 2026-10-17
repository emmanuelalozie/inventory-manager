package com.example.inventix.service;

import com.example.inventix.model.MovementReason;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only reports over products, the stock ledger and orders.
 */
public interface ReportService {

    int MAX_TOP_SELLERS = 100;

    /** Stock on hand valued at selling price and at unit cost, per category and in total. */
    StockValue stockValue();

    /** Per movement reason: how many movements and how many units in and out, within the range. */
    List<MovementSummary> movementSummary(DateRange range);

    /**
     * Products by units sold on SHIPPED and DELIVERED orders created within the range, most first.
     *
     * @throws com.example.inventix.exception.BadRequestException if limit is not between 1 and {@link #MAX_TOP_SELLERS}
     */
    List<TopSeller> topSellers(DateRange range, int limit);

    record StockValue(Totals total, List<CategoryValue> categories) {
    }

    /**
     * @param valueAtCost         products without a unit cost count as 0 here; see productsWithoutCost
     * @param productsWithoutCost how many products have no unit cost, so valueAtCost is understated
     */
    record Totals(long products, long units, BigDecimal valueAtPrice, BigDecimal valueAtCost,
                  long productsWithoutCost) {
    }

    /** categoryId and categoryName are null for products without a category. */
    record CategoryValue(Long categoryId, String categoryName, long products, long units, BigDecimal valueAtPrice,
                         BigDecimal valueAtCost, long productsWithoutCost) {
    }

    record MovementSummary(MovementReason reason, long movements, long unitsIn, long unitsOut) {
    }

    record TopSeller(Long productId, String name, String sku, long unitsSold, BigDecimal revenue) {
    }
}
