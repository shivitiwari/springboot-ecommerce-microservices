package com.onlineshopping.notification_service.controller;

import com.onlineshopping.notification_service.entity.NotificationLog;
import com.onlineshopping.notification_service.repository.NotificationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;

    /** Get all notifications for the authenticated user (reads X-User-Id from Gateway) */
    @GetMapping("/my")
    public ResponseEntity<List<NotificationLog>> getMyNotifications(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        if (userIdStr == null || userIdStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = Long.parseLong(userIdStr);
        return ResponseEntity.ok(notificationLogRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    /** Get all notifications for a specific order (admin use) */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<NotificationLog>> getByOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(notificationLogRepository.findByOrderId(orderId));
    }
}

