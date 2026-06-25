package com.onlineshopping.inventory_service.consumer;

import com.onlineshopping.inventory_service.dto.OrderEvent;
import com.onlineshopping.inventory_service.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderEventConsumer {

    private final InventoryService inventoryService;

    public OrderEventConsumer(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Consumes from order-events topic (3 partitions).
     * containerFactory = "kafkaListenerContainerFactory" → MANUAL ack mode.
     * Acknowledgment is passed to service — offset only committed after successful DB update.
     */
    @KafkaListener(
        topics = "order-events",
        groupId = "inventory-service-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderEvent(
            ConsumerRecord<String, OrderEvent> record,
            Acknowledgment acknowledgment) {

        log.info("[KAFKA] Received → topic={} | partition={} | offset={} | orderId={}",
                record.topic(), record.partition(), record.offset(),
                record.value().getOrderId());

        inventoryService.processOrderEvent(record.value(), acknowledgment);
    }
}

