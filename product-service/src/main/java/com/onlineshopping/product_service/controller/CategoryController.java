package com.onlineshopping.product_service.controller;

import com.onlineshopping.product_service.dto.ApiResponse;
import com.onlineshopping.product_service.entity.Category;
import com.onlineshopping.product_service.exception.CategoryNotFoundException;
import com.onlineshopping.product_service.repo.CategoryRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Category Controller for managing product categories.
 * 
 * Security:
 * - GET endpoints are public (configured in SecurityConfig)
 * - POST/PUT/DELETE endpoints require ADMIN role
 */
@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Get all categories - PUBLIC
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<Category>>> getAllCategories() {
        List<Category> categories = categoryRepository.findAll();
        return ResponseEntity.ok(
                ApiResponse.<List<Category>>builder()
                        .success(true)
                        .message("Categories fetched successfully")
                        .data(categories)
                        .build()
        );
    }

    /**
     * Get category by ID - PUBLIC
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Category>> getCategoryById(@PathVariable Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        return ResponseEntity.ok(
                ApiResponse.<Category>builder()
                        .success(true)
                        .message("Category fetched successfully")
                        .data(category)
                        .build()
        );
    }

    /**
     * Create a new category - ADMIN ONLY
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Category>> createCategory(@Valid @RequestBody Category category) {
        Category savedCategory = categoryRepository.save(category);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.<Category>builder()
                        .success(true)
                        .message("Category created successfully")
                        .data(savedCategory)
                        .build()
        );
    }

    /**
     * Update an existing category - ADMIN ONLY
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Category>> updateCategory(
            @PathVariable Long id,
            @Valid @RequestBody Category categoryDetails) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        
        category.setName(categoryDetails.getName());
        Category updatedCategory = categoryRepository.save(category);
        
        return ResponseEntity.ok(
                ApiResponse.<Category>builder()
                        .success(true)
                        .message("Category updated successfully")
                        .data(updatedCategory)
                        .build()
        );
    }

    /**
     * Delete a category - ADMIN ONLY
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        
        categoryRepository.delete(category);
        
        return ResponseEntity.ok(
                ApiResponse.<Void>builder()
                        .success(true)
                        .message("Category deleted successfully")
                        .data(null)
                        .build()
        );
    }
}


