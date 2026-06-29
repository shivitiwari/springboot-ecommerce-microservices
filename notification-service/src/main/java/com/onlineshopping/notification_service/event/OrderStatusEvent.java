package com.onlineshopping.notification_service.event;

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
public class OrderStatusEvent {

    private String orderId;          // MongoDB Order ID from Order Service
    private Long userId;
    private String userEmail;        // REQUIRED — used for sending email (no User Service call needed)
    private String status;           // CONFIRMED, FAILED, SHIPPED, DELIVERED, CANCELLED
    private Double totalAmount;
    private List<OrderItemEvent> items;
    private String failureReason;    // null on success, message on failure (e.g., "Insufficient stock")
    private LocalDateTime timestamp;
}

