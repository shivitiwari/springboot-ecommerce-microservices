package com.onlineshopping.product_service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO for creating or updating a product.
 * All fields are validated using Jakarta Validation annotations.
 */
@Data
@NoArgsConstructor
public class CreateProduct {
    
    @NotBlank(message = "Product name is required")
    private String name;
    
    @NotBlank(message = "Description is required")
    private String description;
    
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    private BigDecimal price;
    
    @Min(value = 0, message = "Stock cannot be negative")
    private int stock;
    
    @NotNull(message = "Category ID is required")
    private Long categoryId;
}
