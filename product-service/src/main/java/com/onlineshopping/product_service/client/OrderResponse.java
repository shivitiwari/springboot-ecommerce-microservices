package com.onlineshopping.product_service.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for Order Service responses.
 * Maps to the Order entity structure from Order Service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    
    private Long orderId;
    private Long userId;
    private String status;
    private BigDecimal totalAmount;
    private LocalDateTime orderDate;
    private List<OrderItemResponse> items;

    /**
     * Nested DTO for order items.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemResponse {
        private Long productId;
        private String productName;
        private int quantity;
        private BigDecimal price;
    }
}

