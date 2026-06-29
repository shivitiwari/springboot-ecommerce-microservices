package com.onlineshopping.notification_service.consumer;

import com.onlineshopping.notification_service.event.OrderStatusEvent;
import com.onlineshopping.notification_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusEventConsumer {

    private final NotificationService notificationService;

    /**
     * Consumes from order-status-updated topic (3 partitions).
     *
     * Events arrive here from:
     *   - Inventory Service → status=CONFIRMED (stock reserved OK)
     *   - Order Service     → status=FAILED (Saga compensation after inventory failure)
     *   - Order Service     → status=SHIPPED, DELIVERED, CANCELLED (future updates)
     *
     * Manual ack: offset committed only after email sent + DB logged.
     */
    @KafkaListener(
        topics = "order-status-updated",
        groupId = "notification-service-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderStatusEvent(
            ConsumerRecord<String, OrderStatusEvent> record,
            Acknowledgment ack) {

        OrderStatusEvent event = record.value();
        log.info("[KAFKA] Received → topic={} | partition={} | offset={} | orderId={} | status={}",
                record.topic(), record.partition(), record.offset(),
                event.getOrderId(), event.getStatus());

        try {
            notificationService.processStatusNotification(event);
            ack.acknowledge();
            log.info("[KAFKA] Offset committed for orderId={}", event.getOrderId());
        } catch (Exception e) {
            log.error("[KAFKA] Failed to process notification for orderId={}: {}",
                    event.getOrderId(), e.getMessage());
            // DON'T acknowledge — Kafka will redeliver on next poll/restart
            // TODO: Add dead-letter topic after N retries
        }
    }
}

