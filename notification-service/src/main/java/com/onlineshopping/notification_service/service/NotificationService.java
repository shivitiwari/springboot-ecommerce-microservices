package com.onlineshopping.notification_service.service;

import com.onlineshopping.notification_service.event.OrderStatusEvent;

public interface NotificationService {
    void processStatusNotification(OrderStatusEvent event);
}

