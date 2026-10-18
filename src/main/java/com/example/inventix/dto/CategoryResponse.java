package com.example.inventix.dto;

import com.example.inventix.model.Category;

import java.util.List;

/**
 * A category with the number of products in it.
 */
public record CategoryResponse(Long id, String name, long productCount, Long version) {

    public static CategoryResponse from(Category category, long productCount) {
        return new CategoryResponse(category.getId(), category.getName(), productCount, category.getVersion());
    }

    public static List<CategoryResponse> fromAll(List<Category> categories, java.util.Map<Long, Long> counts) {
        return categories.stream().map(c -> from(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }
}
