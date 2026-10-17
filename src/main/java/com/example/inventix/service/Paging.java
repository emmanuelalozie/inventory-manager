package com.example.inventix.service;

import com.example.inventix.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Set;

/**
 * Turns the optional page, size and sort request parameters into a {@link Pageable}.
 * Without page and size the whole list is returned, as before paging existed.
 */
public final class Paging {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 500;

    private Paging() {
    }

    /**
     * @throws BadRequestException if page is negative or size is not between 1 and {@link #MAX_PAGE_SIZE}
     */
    public static Pageable pageable(Integer page, Integer size, Sort sort) {
        if (page == null && size == null) {
            return Pageable.unpaged(sort);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0) {
            throw new BadRequestException("page must be 0 or greater");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(pageNumber, pageSize, sort);
    }

    /**
     * Parses "field" or "field,asc|desc". Only the given fields are accepted, so the sort can't reach other
     * columns; ties are broken by id so pages don't overlap.
     *
     * @throws BadRequestException for an unknown field or direction
     */
    public static Sort sort(String value, Set<String> allowedFields, String defaultField) {
        String text = value == null || value.isBlank() ? defaultField : value.trim();
        String[] parts = text.split(",", -1);
        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            throw new BadRequestException("sort must be one of " + String.join(", ", allowedFields.stream().sorted().toList()));
        }
        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 2) {
            throw new BadRequestException("sort must look like 'field' or 'field,desc'");
        }
        if (parts.length == 2) {
            String dir = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!dir.equals("asc") && !dir.equals("desc")) {
                throw new BadRequestException("sort direction must be asc or desc");
            }
            direction = dir.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        }
        Sort sort = Sort.by(direction, field);
        return field.equals("id") ? sort : sort.and(Sort.by("id"));
    }
}
