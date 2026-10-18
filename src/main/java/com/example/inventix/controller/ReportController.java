package com.example.inventix.controller;

import com.example.inventix.service.DateRange;
import com.example.inventix.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Reports. from and to are dates (2026-10-01), both included; leave either out for an open range.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    static final String DEFAULT_TOP_SELLERS = "10";

    private final ReportService reportService;

    @Autowired
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/stock-value")
    public ReportService.StockValue stockValue() {
        return reportService.stockValue();
    }

    @GetMapping("/movements")
    public List<ReportService.MovementSummary> movementSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.movementSummary(DateRange.of(from, to));
    }

    @GetMapping("/top-sellers")
    public List<ReportService.TopSeller> topSellers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = DEFAULT_TOP_SELLERS) int limit) {
        return reportService.topSellers(DateRange.of(from, to), limit);
    }
}
