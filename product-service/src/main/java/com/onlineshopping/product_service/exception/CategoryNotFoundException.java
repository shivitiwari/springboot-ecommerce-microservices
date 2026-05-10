package com.onlineshopping.product_service.exception;

/**
 * Exception thrown when a category is not found.
 */
public class CategoryNotFoundException extends RuntimeException {
    
    public CategoryNotFoundException(Long id) {
        super("Category not found with id: " + id);
    }
    
    public CategoryNotFoundException(String message) {
        super(message);
    }
}

