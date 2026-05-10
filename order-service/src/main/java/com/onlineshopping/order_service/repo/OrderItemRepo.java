package com.onlineshopping.order_service.repo;

import com.onlineshopping.order_service.entity.OrderItem;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderItemRepo extends MongoRepository<OrderItem, Long> {
}
