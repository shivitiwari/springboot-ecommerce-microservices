package com.onlineshopping.notification_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderId;          // MongoDB Order ID

    @Column(nullable = false)
    private Long userId;

    private String recipientEmail;

    @Column(nullable = false)
    private String type;             // ORDER_CONFIRMED, ORDER_FAILED, ORDER_SHIPPED, etc.

    @Column(nullable = false)
    private String status;           // SENT, FAILED, PENDING

    @Column(length = 500)
    private String message;

    private LocalDateTime createdAt;
}

