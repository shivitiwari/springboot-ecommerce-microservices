package com.onlineShopping.UserService.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
