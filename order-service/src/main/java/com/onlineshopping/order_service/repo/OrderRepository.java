package com.onlineshopping.order_service.repo;

import com.onlineshopping.order_service.entity.Order;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findTopByUserIdOrderByCreatedAtDesc(Long userId);
}
