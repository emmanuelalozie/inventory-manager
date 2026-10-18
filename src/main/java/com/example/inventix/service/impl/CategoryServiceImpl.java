package com.example.inventix.service.impl;

import com.example.inventix.exception.ConflictException;
import com.example.inventix.exception.NotFoundException;
import com.example.inventix.model.Category;
import com.example.inventix.repository.CategoryRepository;
import com.example.inventix.repository.ProductRepository;
import com.example.inventix.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Autowired
    public CategoryServiceImpl(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Override
    public List<Category> getAllCategories() {
        return categoryRepository.findAllByOrderByNameAsc();
    }

    @Override
    public Map<Long, Long> countProductsByCategory() {
        Map<Long, Long> counts = new HashMap<>();
        productRepository.countByCategory().forEach(row -> counts.put((Long) row[0], ((Number) row[1]).longValue()));
        return counts;
    }

    @Override
    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Category not found with id: " + id));
    }

    @Override
    public Category createCategory(String name) {
        String clean = name.strip();
        if (categoryRepository.existsByNameIgnoreCase(clean)) {
            throw new ConflictException("A category named '" + clean + "' already exists");
        }
        Category category = new Category();
        category.setName(clean);
        return categoryRepository.save(category);
    }

    @Override
    public Category renameCategory(Long id, String name, Long version) {
        Category category = getCategory(id);
        if (version != null && !version.equals(category.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Category.class, id);
        }
        String clean = name.strip();
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(clean, id)) {
            throw new ConflictException("A category named '" + clean + "' already exists");
        }
        category.setName(clean);
        return categoryRepository.save(category);
    }

    @Override
    public void deleteCategory(Long id) {
        Category category = getCategory(id);
        if (productRepository.existsByCategoryId(id)) {
            throw new ConflictException("Category '" + category.getName()
                    + "' still has products. Move them to another category first.");
        }
        categoryRepository.delete(category);
    }
}
