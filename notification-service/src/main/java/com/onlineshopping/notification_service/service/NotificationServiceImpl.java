package com.onlineshopping.notification_service.service;

import com.onlineshopping.notification_service.entity.NotificationLog;
import com.onlineshopping.notification_service.event.OrderStatusEvent;
import com.onlineshopping.notification_service.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final JavaMailSender mailSender;
    private final NotificationLogRepository notificationLogRepository;

    @Override
    public void processStatusNotification(OrderStatusEvent event) {
        String type = "ORDER_" + event.getStatus();

        // Idempotency check — don't send duplicate email if same orderId+status arrives twice
        if (notificationLogRepository.existsByOrderIdAndType(event.getOrderId(), type)) {
            log.info("[IDEMPOTENT] Already sent for orderId={}, status={}", event.getOrderId(), event.getStatus());
            return;
        }

        // 1. Send appropriate email based on status
        sendStatusEmail(event);

        // 2. Log notification to DB
        NotificationLog logEntry = NotificationLog.builder()
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .recipientEmail(event.getUserEmail())
                .type(type)     // ORDER_CONFIRMED, ORDER_FAILED, etc.
                .status("SENT")
                .message(buildLogMessage(event))
                .createdAt(LocalDateTime.now())
                .build();

        notificationLogRepository.save(logEntry);
        log.info("[NOTIFICATION] Logged: type={}, orderId={}", logEntry.getType(), event.getOrderId());
    }

    private void sendStatusEmail(OrderStatusEvent event) {
        if (event.getUserEmail() == null || event.getUserEmail().isBlank()) {
            log.warn("[EMAIL] No email for userId={}, skipping. orderId={}",
                    event.getUserId(), event.getOrderId());
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.getUserEmail());
        message.setSubject(buildSubject(event));
        message.setText(buildEmailBody(event));

        mailSender.send(message);
        log.info("[EMAIL] Sent {} notification to {} for orderId={}",
                event.getStatus(), event.getUserEmail(), event.getOrderId());
    }

    private String buildSubject(OrderStatusEvent event) {
        return switch (event.getStatus()) {
            case "CONFIRMED" -> "✅ Order Confirmed - #" + event.getOrderId();
            case "FAILED"    -> "❌ Order Failed - #" + event.getOrderId();
            case "SHIPPED"   -> "🚚 Order Shipped - #" + event.getOrderId();
            case "DELIVERED"  -> "📦 Order Delivered - #" + event.getOrderId();
            case "CANCELLED" -> "🚫 Order Cancelled - #" + event.getOrderId();
            default          -> "📋 Order Update - #" + event.getOrderId();
        };
    }

    private String buildEmailBody(OrderStatusEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello,\n\n");

        switch (event.getStatus()) {
            case "CONFIRMED" -> {
                sb.append("Your order has been CONFIRMED! Stock has been reserved.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Total      : ₹").append(event.getTotalAmount()).append("\n");
            }
            case "FAILED" -> {
                sb.append("Unfortunately, your order could NOT be processed.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Reason     : ").append(event.getFailureReason()).append("\n");
                sb.append("\nPlease try again or contact support.\n");
            }
            case "SHIPPED" -> {
                sb.append("Your order has been SHIPPED!\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Total      : ₹").append(event.getTotalAmount()).append("\n");
            }
            case "DELIVERED" -> {
                sb.append("Your order has been DELIVERED!\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
            }
            case "CANCELLED" -> {
                sb.append("Your order has been CANCELLED.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Reason     : ").append(event.getFailureReason() != null ?
                        event.getFailureReason() : "Cancelled by user").append("\n");
            }
            default -> {
                sb.append("Your order status has been updated to: ").append(event.getStatus()).append("\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
            }
        }

        sb.append("\nTimestamp  : ").append(event.getTimestamp()).append("\n");

        if (event.getItems() != null && !event.getItems().isEmpty()) {
            sb.append("\nItems Ordered:\n");
            event.getItems().forEach(item ->
                sb.append("  - ").append(item.getProductName())
                  .append(" x").append(item.getQuantity())
                  .append(" @ ₹").append(item.getPrice()).append("\n")
            );
        }

        sb.append("\nThank you for shopping with us!\n");
        sb.append("Team Online Shopping");
        return sb.toString();
    }

    private String buildLogMessage(OrderStatusEvent event) {
        return switch (event.getStatus()) {
            case "CONFIRMED" -> "Order confirmed, stock reserved. Order #" + event.getOrderId();
            case "FAILED"    -> "Order failed: " + event.getFailureReason() + ". Order #" + event.getOrderId();
            case "SHIPPED"   -> "Order shipped. Order #" + event.getOrderId();
            case "DELIVERED"  -> "Order delivered. Order #" + event.getOrderId();
            case "CANCELLED" -> "Order cancelled. Order #" + event.getOrderId();
            default          -> "Status update: " + event.getStatus() + ". Order #" + event.getOrderId();
        };
    }
}

