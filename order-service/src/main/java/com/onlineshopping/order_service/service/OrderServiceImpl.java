package com.onlineshopping.order_service.service;

import com.onlineshopping.order_service.client.ProductClient;
import com.onlineshopping.order_service.client.UserClient;
import com.onlineshopping.order_service.dto.NewOrder;
import com.onlineshopping.order_service.dto.OrderResponse;
import com.onlineshopping.order_service.dto.ProductResponse;
import com.onlineshopping.order_service.dto.UserResponse;
import com.onlineshopping.order_service.entity.Order;
import com.onlineshopping.order_service.kafka.KafkaProducerService;
import com.onlineshopping.order_service.repo.OrderRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.naming.ServiceUnavailableException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final UserClient userClient;
    private final ProductClient productClient;
    private final KafkaProducerService kafkaProducerService;

    public OrderServiceImpl(OrderRepository orderRepository, UserClient userClient, ProductClient productClient, KafkaProducerService kafkaProducerService, KafkaProducerService kafkaProducerService1) {
        this.orderRepository = orderRepository;
        this.userClient = userClient;
        this.productClient = productClient;
        this.kafkaProducerService = kafkaProducerService1;
    }
    //Circuit breaker for user response
    @CircuitBreaker(name = "userService", fallbackMethod = "userFallback")
    public UserResponse validateUser(Long userId) {
        return userClient.getUserById(userId);
    }

    public UserResponse userFallback(Long userId, Exception e) throws ServiceUnavailableException {
        log.error("User service unavailable for userId: {}", userId);
        throw new ServiceUnavailableException("User service temporarily unavailable");
    }
    //Circuit breaker for product response
    @CircuitBreaker(name = "productService", fallbackMethod = "productFallback")
    public ProductResponse validateProduct(Long productId) {
        return productClient.getProductById(productId);
    }

    public ProductResponse productFallback(Long productId, Exception e) throws ServiceUnavailableException {
        log.error("Product service unavailable for productId: {}", productId);
        throw new ServiceUnavailableException("Product service temporarily unavailable");
    }

    @Override
    public void placeOrder(NewOrder neworder) {
        UserResponse user = validateUser(neworder.getUserId());
        if (user == null) {
            throw new RuntimeException("User not found");
        }
        // Calculate total
        double totalAmount = 0;
        for (var item : neworder.getItems()) {
            ProductResponse productResponse = validateProduct(item.getProductId());
            if (productResponse == null) {
                throw new RuntimeException("Product not found: " + item.getProductId() + ":" + item.getProductName());
            }
            item.setProductName(productResponse.getName());
            item.setPrice(productResponse.getPrice());
            totalAmount += productResponse.getPrice() * item.getQuantity();
        }
        Order order = new Order();
        order.setUserId(neworder.getUserId());
        order.setTotalAmount(totalAmount);
        order.setStatus("PLACED");
        order.setItems(neworder.getItems());
        order.setCreatedAt(LocalDateTime.now());
        //Save to Database
        Order savedOrder = orderRepository.save(order);
        //Send Kafka event after saving
        kafkaProducerService.sendOrderEvent(savedOrder);
    }

    @Override
    @Cacheable(value = "orders", key = "#id")
    public OrderResponse getOrderById(String id) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new RuntimeException("Order Not found"));
        return mapToResponse(order);
    }

    @Override
    public List<OrderResponse> getUserOrders(Long userId) {
        Optional<Order> orders = orderRepository.findTopByUserIdOrderByCreatedAtDesc(userId);
        if (orders.isEmpty()) {
            throw new RuntimeException("No Orders found");
        }
        return orders.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @CacheEvict(value = "orders", key = "#id")
    public void updateOrderStatus(String id, String status) {
        Order order = orderRepository.findById(id).orElseThrow(() -> new RuntimeException("Order Not found"));
        order.setStatus(status);
        orderRepository.save(order);
    }

    private OrderResponse mapToResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .items(order.getItems())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt())
                .build();
    }
}
