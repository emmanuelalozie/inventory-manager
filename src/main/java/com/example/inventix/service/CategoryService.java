package com.example.inventix.service;

import com.example.inventix.model.Category;

import java.util.List;
import java.util.Map;

public interface CategoryService {

    /** All categories, by name. */
    List<Category> getAllCategories();

    /** Number of products per category id (categories without products are left out). */
    Map<Long, Long> countProductsByCategory();

    /**
     * @throws com.example.inventix.exception.NotFoundException if there is no category with this id
     */
    Category getCategory(Long id);

    /**
     * @throws com.example.inventix.exception.ConflictException if another category has the same name (ignoring case)
     */
    Category createCategory(String name);

    /** Renames a category. A version that doesn't match the stored one is rejected with 409. */
    Category renameCategory(Long id, String name, Long version);

    /**
     * @throws com.example.inventix.exception.ConflictException if products are still in the category
     */
    void deleteCategory(Long id);
}
