package com.example.inventix.controller;

import com.example.inventix.dto.CategoryRequest;
import com.example.inventix.dto.CategoryResponse;
import com.example.inventix.model.Category;
import com.example.inventix.service.CategoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    @Autowired
    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** All categories by name, each with its number of products. */
    @GetMapping
    public List<CategoryResponse> getAllCategories() {
        return CategoryResponse.fromAll(categoryService.getAllCategories(), categoryService.countProductsByCategory());
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = CategoryResponse.from(categoryService.createCategory(request.name()), 0);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public CategoryResponse renameCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.renameCategory(id, request.name(), request.version());
        Map<Long, Long> counts = categoryService.countProductsByCategory();
        return CategoryResponse.from(category, counts.getOrDefault(id, 0L));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
