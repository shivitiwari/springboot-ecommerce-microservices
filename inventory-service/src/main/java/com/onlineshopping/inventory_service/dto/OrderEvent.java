package com.onlineshopping.inventory_service.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private List<OrderItemEvent> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}

