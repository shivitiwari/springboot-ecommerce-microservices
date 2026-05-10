package com.onlineshopping.product_service.dto;

import com.onlineshopping.product_service.entity.Category;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
    private long productId;
    private String name;
    private String description;
    private BigDecimal price;
    private int stock;
    private Long categoryId;
    private String categoryName;
}
