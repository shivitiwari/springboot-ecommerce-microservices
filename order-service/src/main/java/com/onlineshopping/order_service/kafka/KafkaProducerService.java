package com.onlineshopping.order_service.kafka;

import com.onlineshopping.order_service.dto.OrderEvent;
import com.onlineshopping.order_service.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendOrderEvent(Order order) {
        OrderEvent event = new OrderEvent(
                order.getId(),
                order.getUserId(),
                order.getItems(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getCreatedAt());
        kafkaTemplate.send("order-events", event.getOrderId(), event);
        log.info("Order event sent successfully. OrderId={}", order.getId());
    }
}
