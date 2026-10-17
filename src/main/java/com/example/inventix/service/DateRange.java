package com.example.inventix.service;

import com.example.inventix.exception.BadRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A range of whole days, both ends included: from 00:00 on {@code from} up to (not including) 00:00 the day after
 * {@code to}. Either end may be open (null). Timestamps are local date-times, like the rest of the data.
 */
public record DateRange(LocalDate from, LocalDate to) {

    public static final DateRange ALL = new DateRange(null, null);

    /**
     * @throws BadRequestException if from is after to
     */
    public static DateRange of(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from (" + from + ") must not be after to (" + to + ")");
        }
        return new DateRange(from, to);
    }

    /** Start of the range, or null when open. */
    public LocalDateTime start() {
        return from == null ? null : from.atStartOfDay();
    }

    /** First moment after the range, or null when open. */
    public LocalDateTime endExclusive() {
        return to == null ? null : to.plusDays(1).atStartOfDay();
    }
}
