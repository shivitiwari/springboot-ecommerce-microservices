package com.onlineshopping.inventory_service.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestockRequest {
    private Integer quantity;
}

