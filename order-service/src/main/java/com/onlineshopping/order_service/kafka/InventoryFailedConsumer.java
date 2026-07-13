package com.onlineshopping.order_service.kafka;

import com.onlineshopping.order_service.dto.InventoryFailedEvent;
import com.onlineshopping.order_service.dto.OrderStatusEvent;
import com.onlineshopping.order_service.repo.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class InventoryFailedConsumer {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public InventoryFailedConsumer(OrderRepository orderRepository,
                                   KafkaTemplate<String, Object> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = "inventory-failed",
            groupId = "order-service-saga-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onInventoryFailed(InventoryFailedEvent event, Acknowledgment ack) {
        log.warn("[SAGA] Inventory failed for orderId={} reason={}",
                event.getOrderId(), event.getFailureReason());
        try {
            // 1. Compensating transaction — mark order FAILED in MongoDB
            orderRepository.findById(event.getOrderId()).ifPresentOrElse(order -> {
                order.setStatus("FAILED");
                orderRepository.save(order);
                log.info("[SAGA] Order {} marked FAILED", event.getOrderId());
            }, () -> log.error("[SAGA] Order {} not found for compensation", event.getOrderId()));

            // 2. Publish FAILED status to order-status-updated
            //    → Notification Service picks this up and sends failure email
            OrderStatusEvent statusEvent = OrderStatusEvent.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .userEmail(event.getUserEmail())
                    .status("FAILED")
                    .totalAmount(event.getTotalAmount())
                    .items(event.getItems())
                    .failureReason(event.getFailureReason())
                    .timestamp(LocalDateTime.now())
                    .build();

            kafkaTemplate.send("order-status-updated", event.getOrderId(), statusEvent);
            log.info("[SAGA] Published FAILED status event for orderId={}", event.getOrderId());

            // 3. Commit offset only after both DB update and publish succeed
            ack.acknowledge();

        } catch (Exception e) {
            // Don't ack — Kafka will redeliver so the compensation can be retried
            log.error("[SAGA] Failed to process inventory-failed for orderId={}",
                    event.getOrderId(), e);
        }
    }
}