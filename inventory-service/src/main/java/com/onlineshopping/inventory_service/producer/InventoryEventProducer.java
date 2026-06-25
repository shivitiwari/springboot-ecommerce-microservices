package com.onlineshopping.inventory_service.producer;

import com.onlineshopping.inventory_service.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class InventoryEventProducer {

    private static final String TOPIC_STATUS_UPDATED = "order-status-updated";
    private static final String TOPIC_INVENTORY_FAILED = "inventory-failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventoryEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes CONFIRMED event → Notification Service sends success email */
    public void publishOrderConfirmed(OrderEvent orderEvent) {
        OrderStatusEvent event = OrderStatusEvent.builder()
                .orderId(orderEvent.getOrderId())
                .userId(orderEvent.getUserId())
                .userEmail(orderEvent.getUserEmail())
                .status("CONFIRMED")
                .totalAmount(orderEvent.getTotalAmount())
                .items(orderEvent.getItems())
                .failureReason(null)
                .timestamp(LocalDateTime.now())
                .build();

        kafkaTemplate.send(TOPIC_STATUS_UPDATED, orderEvent.getOrderId(), event);
        log.info("[KAFKA] → {} | orderId={} | status=CONFIRMED",
                TOPIC_STATUS_UPDATED, orderEvent.getOrderId());
    }

    /** Publishes inventory-failed event → Order Service Saga compensation */
    public void publishInventoryFailed(OrderEvent orderEvent, String reason) {
        InventoryFailedEvent event = InventoryFailedEvent.builder()
                .orderId(orderEvent.getOrderId())
                .userId(orderEvent.getUserId())
                .userEmail(orderEvent.getUserEmail())
                .failureReason(reason)
                .items(orderEvent.getItems())
                .totalAmount(orderEvent.getTotalAmount())
                .timestamp(LocalDateTime.now())
                .build();

        kafkaTemplate.send(TOPIC_INVENTORY_FAILED, orderEvent.getOrderId(), event);
        log.warn("[KAFKA] → {} | orderId={} | reason={}",
                TOPIC_INVENTORY_FAILED, orderEvent.getOrderId(), reason);
    }
}

