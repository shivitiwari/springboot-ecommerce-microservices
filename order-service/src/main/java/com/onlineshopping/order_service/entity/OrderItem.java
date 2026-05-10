package com.onlineshopping.order_service.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
