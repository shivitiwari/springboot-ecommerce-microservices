package com.onlineshopping.order_service.dto;

import com.onlineshopping.order_service.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {
    private String id;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status; // PENDING, CONFIRMED, DELIVERED
    private LocalDateTime createdAt;
}
