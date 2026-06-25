package com.onlineshopping.inventory_service.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InitInventoryRequest {
    private Long productId;
    private String productName;
    private Integer quantity;
}

