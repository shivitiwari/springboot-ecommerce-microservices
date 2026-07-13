package com.onlineshopping.order_service.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String status;
    private Double totalAmount;
    private List<OrderItem> items;
    private String failureReason;
    private LocalDateTime timestamp;
}