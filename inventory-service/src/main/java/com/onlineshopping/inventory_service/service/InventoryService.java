package com.onlineshopping.inventory_service.service;

import com.onlineshopping.inventory_service.dto.OrderEvent;
import org.springframework.kafka.support.Acknowledgment;

public interface InventoryService {
    void processOrderEvent(OrderEvent event, Acknowledgment ack);
}

