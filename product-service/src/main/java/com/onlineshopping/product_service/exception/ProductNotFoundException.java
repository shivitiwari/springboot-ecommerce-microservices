package com.onlineshopping.product_service.exception;

/**
 * Exception thrown when a product is not found.
 */
public class ProductNotFoundException extends RuntimeException {
    
    public ProductNotFoundException(Long id) {
        super("Product not found with id: " + id);
    }
    
    public ProductNotFoundException(String message) {
        super(message);
    }
}

