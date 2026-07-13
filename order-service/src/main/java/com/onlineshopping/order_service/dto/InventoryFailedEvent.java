package com.onlineshopping.order_service.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryFailedEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String failureReason;
    private List<OrderItem> items;       // reuse your existing OrderItem entity
    private Double totalAmount;
    private LocalDateTime timestamp;
}