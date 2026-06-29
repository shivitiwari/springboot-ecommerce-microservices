package com.onlineshopping.notification_service.repository;

import com.onlineshopping.notification_service.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<NotificationLog> findByOrderId(String orderId);
    List<NotificationLog> findByType(String type);
    boolean existsByOrderIdAndType(String orderId, String type);  // For idempotency check
}

