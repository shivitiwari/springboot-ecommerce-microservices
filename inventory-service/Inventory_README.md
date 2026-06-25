# Inventory Service (`inventory-service`)

Inventory management microservice for the **Online Shopping** system.  
It **consumes order events from Kafka**, checks and updates stock levels, and participates in the **Choreography-Based Saga Pattern** for failure compensation.  
It does **NOT** validate JWT — user identity is trusted via `X-User-*` headers forwarded by the API Gateway.

---

## 📋 Service Overview

| Property | Value |
|----------|-------|
| **Service Name** | `INVENTORY-SERVICE` |
| **Port** | `8084` |
| **Base Path** | `/inventory` |
| **Gateway Route** | `/api/inventory/**` → `lb://INVENTORY-SERVICE` |
| **Database** | MySQL (`inventory_db`) |
| **Messaging** | Kafka — Consumer (`order-events`) + Producer (`order-status-updated`, `inventory-failed`) |
| **Registry** | Eureka Server (`http://localhost:8761/eureka`) |
| **Cache / Lock** | Redis — Distributed lock (`lock:inventory:*`) |

---

## 🏗️ Role in System Architecture

```
                        Client
                          │
                     API Gateway
                          │ (REST/Feign — synchronous)
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
     User Service   Product Service   Order Service
                                          │
                                          │ (1) publishes OrderEvent (status=PLACED)
                                          ▼
                                Kafka: order-events (3 partitions)
                                          │
                                          │ (2) consumes
                                          ▼
                                  ┌──────────────────────┐
                                  │   INVENTORY SERVICE  │
                                  │  (this service)      │
                                  │                      │
                                  │  Check stock         │
                                  │  Decrement if OK     │
                                  │  @Version + SETNX    │
                                  └──────────────────────┘
                                     │             │
                         YES: stock ok         NO: insufficient stock
                              │                        │
                   (3a) publish CONFIRMED    (3b) publish inventory-failed
                   to order-status-updated           │
                              │            (4) Order Service consumes
                              │                 → sets order FAILED (Saga)
                              │                 → publishes FAILED
                              │                   to order-status-updated
                              │                        │
                              └───────────┬────────────┘
                                          ▼
                               Kafka: order-status-updated (3 partitions)
                                          │
                                          │ (5) consumes
                                          ▼
                                 Notification Service
                                 (sends CONFIRMED / FAILED email)
```

---

## 🆕 NEW SERVICE — Full Implementation Guide

This service does not exist yet. This document is the complete build guide.  
Follow sections top-to-bottom in this order: **dependencies → entities → config → consumer → producer → service → controller → security**.

---

## 📁 Project Structure

```
inventory-service/
├── src/main/java/com/onlineshopping/inventory_service/
│   ├── InventoryServiceApplication.java         # @SpringBootApplication @EnableDiscoveryClient
│   ├── config/
│   │   ├── KafkaConfig.java                     # Topic creation (3 partitions), manual-ack factory
│   │   └── RedisConfig.java                     # RedisTemplate bean for distributed lock
│   ├── consumer/
│   │   └── OrderEventConsumer.java              # @KafkaListener on "order-events"
│   ├── controller/
│   │   └── InventoryController.java             # REST: stock check, init, restock
│   ├── dto/
│   │   ├── OrderEvent.java                      # Received from order-service via Kafka
│   │   ├── OrderItemEvent.java                  # Embedded item inside OrderEvent
│   │   ├── OrderStatusEvent.java                # Published to "order-status-updated"
│   │   ├── InventoryFailedEvent.java            # Published to "inventory-failed"
│   │   ├── InitInventoryRequest.java            # REST request: init stock
│   │   └── RestockRequest.java                  # REST request: add stock
│   ├── entity/
│   │   ├── Inventory.java                       # MySQL entity — @Version for optimistic lock
│   │   └── ProcessedEvent.java                  # Idempotency log (orderId → processed)
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── InsufficientStockException.java
│   │   └── InventoryNotFoundException.java
│   ├── producer/
│   │   └── InventoryEventProducer.java          # Publishes to order-status-updated / inventory-failed
│   ├── repository/
│   │   ├── InventoryRepository.java
│   │   └── ProcessedEventRepository.java
│   ├── security/
│   │   ├── GatewayAuthFilter.java               # Reads X-User-Id, X-Username, X-User-Role
│   │   └── SecurityConfig.java                  # ADMIN write, PUBLIC read
│   └── service/
│       ├── InventoryService.java                # Interface
│       └── InventoryServiceImpl.java            # Core logic (lock + optimistic lock + idempotency)
└── src/main/resources/
    └── application.properties
```

---

## ⚠️ REQUIRED CHANGES TO OTHER SERVICES (Before Building This Service)

### 🔴 CRITICAL — Order Service Must Be Updated

Before Inventory Service works end-to-end, Order Service needs the following additions.

---

#### 1. Add `userEmail` to `OrderEvent` DTO

Inventory Service needs to forward `userEmail` in its published events so that Notification Service can send emails **without calling User Service** (decoupled, resilient).

**Update:** `order-service/dto/OrderEvent.java`
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    private String orderId;
    private Long userId;
    private String userEmail;   // ← ADD THIS FIELD
    private List<OrderItemEvent> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

**Populate in `OrderServiceImpl.placeOrder()`:**
```java
// UserClient already returns email — extract it here
UserResponse user = userClient.getUserById(newOrder.getUserId());

OrderEvent event = OrderEvent.builder()
        .orderId(savedOrder.getId())
        .userId(savedOrder.getUserId())
        .userEmail(user.getEmail())   // ← ADD THIS
        .items(itemEvents)
        .totalAmount(savedOrder.getTotalAmount())
        .status(savedOrder.getStatus())
        .createdAt(savedOrder.getCreatedAt())
        .build();

kafkaProducerService.sendOrderEvent(event);
```

---

#### 2. Add `InventoryFailedEvent` DTO to Order Service

**Create:** `order-service/dto/InventoryFailedEvent.java`
```java
package com.onlineshopping.order_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryFailedEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String failureReason;
    private List<OrderItemEvent> items;
    private Double totalAmount;
    private LocalDateTime timestamp;
}
```

---

#### 3. Add `OrderStatusEvent` DTO to Order Service

**Create:** `order-service/dto/OrderStatusEvent.java`
```java
package com.onlineshopping.order_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String status;           // CONFIRMED, FAILED, DELIVERED
    private Double totalAmount;
    private List<OrderItemEvent> items;
    private String failureReason;    // null on success, message on failure
    private LocalDateTime timestamp;
}
```

---

#### 4. Add Kafka Consumer for `inventory-failed` (Saga Compensation)

**Create:** `order-service/kafka/InventoryFailedConsumer.java`

```java
package com.onlineshopping.order_service.kafka;

import com.onlineshopping.order_service.dto.InventoryFailedEvent;
import com.onlineshopping.order_service.dto.OrderStatusEvent;
import com.onlineshopping.order_service.repo.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
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
    public void consumeInventoryFailed(
            ConsumerRecord<String, InventoryFailedEvent> record,
            Acknowledgment ack) {

        InventoryFailedEvent event = record.value();
        log.warn("[SAGA] Inventory failed — orderId={}, reason={}",
                event.getOrderId(), event.getFailureReason());

        try {
            // ── Compensating transaction: mark order FAILED ──────────
            orderRepository.findById(event.getOrderId()).ifPresentOrElse(order -> {
                order.setStatus("FAILED");
                orderRepository.save(order);
                log.info("[SAGA] Order {} marked FAILED", event.getOrderId());
            }, () -> log.warn("[SAGA] Order {} not found for compensation", event.getOrderId()));

            // ── Publish FAILED status for Notification Service ───────
            OrderStatusEvent failedStatusEvent = OrderStatusEvent.builder()
                    .orderId(event.getOrderId())
                    .userId(event.getUserId())
                    .userEmail(event.getUserEmail())
                    .status("FAILED")
                    .totalAmount(event.getTotalAmount())
                    .items(event.getItems())
                    .failureReason(event.getFailureReason())
                    .timestamp(LocalDateTime.now())
                    .build();

            kafkaTemplate.send("order-status-updated", event.getOrderId(), failedStatusEvent);
            log.info("[SAGA] Published FAILED event to order-status-updated, orderId={}",
                    event.getOrderId());

            ack.acknowledge();

        } catch (Exception e) {
            log.error("[SAGA] Compensation failed for orderId={}: {}", event.getOrderId(), e.getMessage());
            // Don't acknowledge — Kafka will redeliver for retry
        }
    }
}
```

**Add to Order Service `application.properties`:**
```properties
# Kafka Consumer (for Saga compensation — consume inventory-failed)
spring.kafka.consumer.group-id=order-service-saga-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=*
```

> **Note:** Order Service `KafkaProducerService` must also be able to produce to `order-status-updated`.  
> Update `KafkaTemplate` bean to accept `Object` as value type (`KafkaTemplate<String, Object>`).

---

## 📦 Data Models

### `Inventory.java` — MySQL Entity

```java
package com.onlineshopping.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory",
       uniqueConstraints = @UniqueConstraint(columnNames = "product_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(nullable = false)
    private Integer quantity;   // Current available stock

    /**
     * OPTIMISTIC LOCKING — @Version
     *
     * JPA appends: WHERE product_id = ? AND version = ?
     * to every UPDATE. If two threads try to decrement stock simultaneously:
     *   Thread A: reads version=0, decrements, saves → version becomes 1 ✅
     *   Thread B: reads version=0, decrements, saves → WHERE version=0 → 0 rows → throws OptimisticLockingFailureException ✅
     *
     * This is the PRIMARY race-condition defense (within a single JVM instance).
     */
    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

---

### `ProcessedEvent.java` — Idempotency Log

```java
package com.onlineshopping.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "order_id")
    private String orderId;    // PRIMARY KEY = orderId → guarantees uniqueness at DB level

    @Column(nullable = false)
    private String result;     // "SUCCESS" or "FAILED"

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}
```

---

### `OrderEvent.java` — Received from Kafka

```java
package com.onlineshopping.inventory_service.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    private String orderId;
    private Long userId;
    private String userEmail;       // forwarded to Notification via outgoing events
    private List<OrderItemEvent> items;
    private Double totalAmount;
    private String status;          // should be "PLACED"
    private LocalDateTime createdAt;
}
```

---

### `OrderItemEvent.java`

```java
package com.onlineshopping.inventory_service.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemEvent {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
```

---

### `OrderStatusEvent.java` — Published to `order-status-updated`

```java
package com.onlineshopping.inventory_service.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String status;           // "CONFIRMED" (published by this service on success)
    private Double totalAmount;
    private List<OrderItemEvent> items;
    private String failureReason;    // null on success
    private LocalDateTime timestamp;
}
```

---

### `InventoryFailedEvent.java` — Published to `inventory-failed`

```java
package com.onlineshopping.inventory_service.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InventoryFailedEvent {
    private String orderId;
    private Long userId;
    private String userEmail;
    private String failureReason;   // e.g. "Insufficient stock for product 1 — required: 5, available: 2"
    private List<OrderItemEvent> items;
    private Double totalAmount;
    private LocalDateTime timestamp;
}
```

---

## ⚙️ Kafka Configuration — `KafkaConfig.java`

```java
package com.onlineshopping.inventory_service.config;

import com.onlineshopping.inventory_service.dto.OrderEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.NewTopic;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

    // ── Topic declarations (3 partitions for parallel processing) ────────────
    // 3 partitions → 3 consumer instances can process in parallel

    @Bean
    public NewTopic orderEventsTopic() {
        // Declared here to ensure it exists before consumption starts
        return TopicBuilder.name("order-events").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderStatusUpdatedTopic() {
        return TopicBuilder.name("order-status-updated").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryFailedTopic() {
        return TopicBuilder.name("inventory-failed").partitions(3).replicas(1).build();
    }

    // ── Manual-Ack Consumer Factory ───────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, OrderEvent> orderEventConsumerFactory(KafkaProperties kp) {
        Map<String, Object> props = new HashMap<>(kp.buildConsumerProperties(null));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);   // Manual commit!
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                  "com.onlineshopping.inventory_service.dto.OrderEvent");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, OrderEvent> cf) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, OrderEvent>();
        factory.setConsumerFactory(cf);
        // MANUAL — offset committed only when ack.acknowledge() is called explicitly
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

---

## 🔴 Race Condition Fix — Two-Layer Lock Strategy

### Why Two Locks?

| Scenario | Problem | Solution Used |
|----------|---------|---------------|
| Two threads on **same JVM instance** try to decrement stock | Both read `quantity=1`, both think OK | `@Version` on `Inventory` — JPA `WHERE version = ?` catches concurrent write, throws `OptimisticLockingFailureException` |
| Two requests on **different instances** (no shared JVM) | `@Version` only works within one JVM's transaction | Redis `SETNX` — global atomic key, only one instance wins |
| Redis crashes | SETNX unavailable | `@Version` still protects within the single running instance |

### Layer 1: `@Version` Optimistic Lock (Same Instance)

JPA generates:
```sql
UPDATE inventory
SET quantity = ?, version = 2
WHERE product_id = ? AND version = 1   ← only succeeds if no one else updated
```
Concurrent write from another thread → `version` mismatch → `OptimisticLockingFailureException`.

### Layer 2: Redis `SETNX` Distributed Lock (Cross-Instance)

```
Instance A:  SETNX lock:inventory:42 → "orderId-abc" (TTL 10s) → returns 1 (acquired) ✅
Instance B:  SETNX lock:inventory:42 → returns 0 (already locked) → throw InsufficientStockException
```

- `SETNX` (SET if Not eXists) is an **atomic** Redis operation — no two instances can both succeed.
- TTL=10s ensures the lock auto-expires if the service crashes mid-update (**prevents deadlock**).
- We store `orderId` as the value (not just `"locked"`) for observability and debugging.

---

## 📨 Kafka Consumer — `OrderEventConsumer.java`

```java
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
```

---

## 🔨 Core Service — `InventoryServiceImpl.java`

```java
package com.onlineshopping.inventory_service.service;

import com.onlineshopping.inventory_service.dto.OrderEvent;
import com.onlineshopping.inventory_service.entity.Inventory;
import com.onlineshopping.inventory_service.entity.ProcessedEvent;
import com.onlineshopping.inventory_service.exception.InsufficientStockException;
import com.onlineshopping.inventory_service.exception.InventoryNotFoundException;
import com.onlineshopping.inventory_service.producer.InventoryEventProducer;
import com.onlineshopping.inventory_service.repository.InventoryRepository;
import com.onlineshopping.inventory_service.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final InventoryEventProducer eventProducer;

    private static final String LOCK_PREFIX = "lock:inventory:";
    private static final long LOCK_TTL_SECONDS = 10L;

    public InventoryServiceImpl(InventoryRepository inventoryRepository,
                                ProcessedEventRepository processedEventRepository,
                                RedisTemplate<String, String> redisTemplate,
                                InventoryEventProducer eventProducer) {
        this.inventoryRepository = inventoryRepository;
        this.processedEventRepository = processedEventRepository;
        this.redisTemplate = redisTemplate;
        this.eventProducer = eventProducer;
    }

    @Override
    public void processOrderEvent(OrderEvent event, Acknowledgment ack) {
        String orderId = event.getOrderId();

        // ── STEP 1: Idempotency check ────────────────────────────────────────
        // If this orderId was already processed (SUCCESS or FAILED), skip.
        // This handles Kafka at-least-once delivery — prevents double stock deduction.
        if (processedEventRepository.existsById(orderId)) {
            log.info("[IDEMPOTENT] orderId={} already processed — skipping and committing offset", orderId);
            ack.acknowledge();
            return;
        }

        try {
            // ── STEP 2: Reserve stock for every item in the order ────────────
            for (var item : event.getItems()) {
                reserveStock(item.getProductId(), item.getQuantity(), orderId);
            }

            // ── STEP 3: Persist idempotency record ───────────────────────────
            processedEventRepository.save(ProcessedEvent.builder()
                    .orderId(orderId)
                    .result("SUCCESS")
                    .processedAt(LocalDateTime.now())
                    .build());

            // ── STEP 4: Publish CONFIRMED event to order-status-updated ──────
            eventProducer.publishOrderConfirmed(event);

            // ── STEP 5: Commit offset (only AFTER successful DB update) ──────
            // This is the key reliability guarantee:
            //   If the service crashes between STEP 2 and here, Kafka redelivers.
            //   Idempotency check (STEP 1) prevents double-deduction on retry.
            ack.acknowledge();
            log.info("[SUCCESS] orderId={} — stock reserved and offset committed", orderId);

        } catch (InsufficientStockException | OptimisticLockingFailureException e) {
            log.error("[FAILURE] Inventory failure for orderId={}: {}", orderId, e.getMessage());

            // Persist FAILED result — prevents infinite retry on business logic failure
            processedEventRepository.save(ProcessedEvent.builder()
                    .orderId(orderId)
                    .result("FAILED")
                    .processedAt(LocalDateTime.now())
                    .build());

            // Publish failure → triggers Order Service Saga compensation
            eventProducer.publishInventoryFailed(event, e.getMessage());

            // Commit offset — business failures (not infrastructure failures) should not retry
            ack.acknowledge();
        }
        // Note: If a DB/Redis infrastructure exception occurs (NOT caught above),
        // ack.acknowledge() is never called → Kafka redelivers → natural retry for infra failures.
    }

    /**
     * Reserves (decrements) stock for one product using two-layer locking.
     *
     * Layer 1 (cross-instance): Redis SETNX distributed lock
     * Layer 2 (within-instance): JPA @Version optimistic lock
     */
    @Transactional
    protected void reserveStock(Long productId, Integer quantity, String orderId) {
        String lockKey = LOCK_PREFIX + productId;

        // ── Layer 1: Redis SETNX — cross-instance lock ───────────────────────
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, orderId, LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(acquired)) {
            throw new InsufficientStockException(
                "Could not acquire inventory lock for productId=" + productId +
                " (concurrent update in progress). orderId=" + orderId);
        }

        try {
            // ── Layer 2: @Version — within-instance optimistic lock ──────────
            Inventory inventory = inventoryRepository.findByProductId(productId)
                    .orElseThrow(() -> new InventoryNotFoundException(
                        "ProductId " + productId + " not found in inventory. " +
                        "Initialize it first via POST /inventory/init"));

            if (inventory.getQuantity() < quantity) {
                throw new InsufficientStockException(String.format(
                    "Insufficient stock for productId=%d — required: %d, available: %d",
                    productId, quantity, inventory.getQuantity()));
            }

            inventory.setQuantity(inventory.getQuantity() - quantity);
            // save() with @Version: JPA appends WHERE version = ? to UPDATE
            // If another thread already incremented version → OptimisticLockingFailureException
            inventoryRepository.save(inventory);

        } finally {
            // ALWAYS release the Redis lock — even on exception
            redisTemplate.delete(lockKey);
        }
    }
}
```

---

## 📤 Kafka Producer — `InventoryEventProducer.java`

```java
package com.onlineshopping.inventory_service.producer;

import com.onlineshopping.inventory_service.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class InventoryEventProducer {

    private static final String TOPIC_STATUS_UPDATED  = "order-status-updated";
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
```

---

## 🌐 REST Controller — `InventoryController.java`

```java
package com.onlineshopping.inventory_service.controller;

import com.onlineshopping.inventory_service.dto.InitInventoryRequest;
import com.onlineshopping.inventory_service.dto.RestockRequest;
import com.onlineshopping.inventory_service.entity.Inventory;
import com.onlineshopping.inventory_service.repository.InventoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryRepository inventoryRepository;

    public InventoryController(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /** GET /inventory/{productId} — Check stock level for a product (PUBLIC) */
    @GetMapping("/{productId}")
    public ResponseEntity<?> getStock(@PathVariable Long productId) {
        return inventoryRepository.findByProductId(productId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Product " + productId + " not found in inventory"));
    }

    /** GET /inventory — List all inventory items (ADMIN) */
    @GetMapping
    public ResponseEntity<List<Inventory>> getAllInventory() {
        return ResponseEntity.ok(inventoryRepository.findAll());
    }

    /** POST /inventory/init — Initialize a new product's stock (ADMIN) */
    @PostMapping("/init")
    public ResponseEntity<String> initInventory(@RequestBody InitInventoryRequest request) {
        if (inventoryRepository.findByProductId(request.getProductId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Inventory for productId=" + request.getProductId() + " already exists");
        }
        Inventory inventory = Inventory.builder()
                .productId(request.getProductId())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .build();
        inventoryRepository.save(inventory);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Inventory initialized: productId=" + request.getProductId() +
                      ", quantity=" + request.getQuantity());
    }

    /** PUT /inventory/{productId}/restock — Add quantity (ADMIN) */
    @PutMapping("/{productId}/restock")
    public ResponseEntity<?> restock(@PathVariable Long productId,
                                     @RequestBody RestockRequest request) {
        return inventoryRepository.findByProductId(productId)
                .map(inv -> {
                    inv.setQuantity(inv.getQuantity() + request.getQuantity());
                    inventoryRepository.save(inv);
                    return ResponseEntity.ok("Restocked productId=" + productId +
                                             " by " + request.getQuantity() +
                                             " units. New stock: " + inv.getQuantity());
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Product " + productId + " not found in inventory"));
    }
}
```

---

## 🛡️ Security

### `GatewayAuthFilter.java`

```java
package com.onlineshopping.inventory_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String userId   = request.getHeader("X-User-Id");
        String username = request.getHeader("X-Username");
        String role     = request.getHeader("X-User-Role");

        if (username != null && !username.isEmpty()) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (role != null && !role.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
```

### `SecurityConfig.java`

```java
package com.onlineshopping.inventory_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayAuthFilter gatewayAuthFilter;

    public SecurityConfig(GatewayAuthFilter gatewayAuthFilter) {
        this.gatewayAuthFilter = gatewayAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST,   "/inventory/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/inventory/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/inventory/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET,    "/inventory/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

---

## ⚙️ Add Routes to API Gateway

### Option A: `application.properties`

```properties
# ================= INVENTORY SERVICE =================
spring.cloud.gateway.routes[4].id=inventory-service-public
spring.cloud.gateway.routes[4].uri=lb://INVENTORY-SERVICE
spring.cloud.gateway.routes[4].predicates[0]=Path=/api/inventory/**
spring.cloud.gateway.routes[4].predicates[1]=Method=GET
spring.cloud.gateway.routes[4].filters[0]=RewritePath=/api/inventory/(?<segment>.*), /inventory/${segment}

spring.cloud.gateway.routes[5].id=inventory-service-protected
spring.cloud.gateway.routes[5].uri=lb://INVENTORY-SERVICE
spring.cloud.gateway.routes[5].predicates[0]=Path=/api/inventory/**
spring.cloud.gateway.routes[5].predicates[1]=Method=POST,PUT,DELETE
spring.cloud.gateway.routes[5].filters[0]=RewritePath=/api/inventory/(?<segment>.*), /inventory/${segment}
```

### Option B: `FilterConfig.java` (Recommended)

Add inside `customRouteLocator` builder in API Gateway's `FilterConfig.java`:

```java
// Route: GET /api/inventory/** → PUBLIC
.route("inventory-service-public", r -> r
        .path("/api/inventory/**")
        .and().method("GET")
        .filters(f -> f.rewritePath("/api/inventory/(?<segment>.*)", "/inventory/${segment}"))
        .uri("lb://INVENTORY-SERVICE"))

// Route: POST/PUT/DELETE /api/inventory/** → JWT Required (ADMIN)
.route("inventory-service-protected", r -> r
        .path("/api/inventory/**")
        .and().method("POST", "PUT", "DELETE")
        .filters(f -> f
                .filter(jwtAuthFilter)
                .rewritePath("/api/inventory/(?<segment>.*)", "/inventory/${segment}"))
        .uri("lb://INVENTORY-SERVICE"))
```

---

## ⚙️ Configuration — `application.properties` (Full)

```properties
# ─────────────────────────────────────────
# APPLICATION
# ─────────────────────────────────────────
spring.application.name=INVENTORY-SERVICE
server.port=8084

# ─────────────────────────────────────────
# MYSQL
# ─────────────────────────────────────────
spring.datasource.url=jdbc:mysql://localhost:3306/inventory_db
spring.datasource.username=root
spring.datasource.password=root
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect

# ─────────────────────────────────────────
# EUREKA
# ─────────────────────────────────────────
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.instance.prefer-ip-address=true

# ─────────────────────────────────────────
# KAFKA CONSUMER
# ─────────────────────────────────────────
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=inventory-service-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=*

# ─────────────────────────────────────────
# KAFKA PRODUCER
# ─────────────────────────────────────────
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer

# ─────────────────────────────────────────
# REDIS (Distributed Lock — SETNX)
# ─────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms

# ─────────────────────────────────────────
# JWT (must match API Gateway exactly)
# ─────────────────────────────────────────
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==

# ─────────────────────────────────────────
# ACTUATOR
# ─────────────────────────────────────────
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.show-details=always

# ─────────────────────────────────────────
# LOGGING
# ─────────────────────────────────────────
logging.level.com.onlineshopping=DEBUG
logging.level.org.springframework.kafka=INFO
logging.level.org.springframework.data.jpa=DEBUG
```

---

## 📦 Dependencies — `pom.xml`

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- JPA + MySQL -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Kafka -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>

    <!-- Redis (Distributed Lock) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Eureka Client -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>

    <!-- Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Actuator + Prometheus -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2023.0.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 🧪 Postman Test Endpoints

**Base URL (via API Gateway):** `http://localhost:8080`  
**Direct URL:** `http://localhost:8084`

| Method | URL | Auth | Body |
|--------|-----|------|------|
| `GET`  | `/api/inventory/{productId}` | None | — |
| `GET`  | `/api/inventory` | Bearer ADMIN | — |
| `POST` | `/api/inventory/init` | Bearer ADMIN | `{"productId":1,"productName":"Laptop","quantity":100}` |
| `PUT`  | `/api/inventory/{productId}/restock` | Bearer ADMIN | `{"quantity":50}` |

**Actuator:**
| URL | Description |
|-----|-------------|
| `http://localhost:8084/actuator/health` | Service health |
| `http://localhost:8084/actuator/prometheus` | Metrics |

---

## 🔄 Kafka Topics Summary

| Topic | Partitions | This Service | Description |
|-------|-----------|--------------|-------------|
| `order-events` | 3 | **CONSUMER** | Receives order placed events from Order Service |
| `order-status-updated` | 3 | **PRODUCER** | Publishes `CONFIRMED` on stock success |
| `inventory-failed` | 3 | **PRODUCER** | Publishes on stock failure → triggers Order Service Saga |

### Consumer Group ID: `inventory-service-group`
- 3 partitions + 3 consumer instances = **fully parallel** processing
- Each partition assigned to one consumer instance — no duplicate processing

### Manual Offset Commit Guarantee
```
Message received from Kafka
        │
        ▼
Idempotency check (ProcessedEvent table)
        │
Already processed? ──YES──► ack.acknowledge() immediately (safe skip)
        │ NO
        ▼
Decrement stock (Redis SETNX + @Version)
        │
DB updated successfully? ──YES──► Publish event ──► ack.acknowledge()
        │ NO (infra failure)
        ▼
Exception NOT caught → ack.acknowledge() NEVER called
        │
        ▼
Kafka redelivers message (retry for infrastructure failures)
```

---

## 🚀 Build & Run

### Prerequisites
1. Java 21+
2. Maven 3.9+
3. MySQL: `CREATE DATABASE inventory_db;`
4. Kafka running on `localhost:9092`
5. Redis running on `localhost:6379`
6. Eureka Server running on `localhost:8761`
7. Order Service must be updated (see [⚠️ REQUIRED CHANGES TO ORDER SERVICE](#️-required-changes-to-other-services-before-building-this-service))

### Create Spring Boot Project
- **Artifact:** `inventory-service`
- **Group:** `com.onlineshopping`
- **Package:** `com.onlineshopping.inventory_service`
- **Spring Boot:** `3.2.x`
- **Java:** `21`

### Run
```bash
./mvnw spring-boot:run
```

### Startup Order
```
1. MySQL  +  Redis  +  Kafka  +  Zookeeper
2. Eureka Server       (port 8761)
3. User Service        (port 8081)
4. Product Service     (port 8082)
5. Order Service       (port 8083)  ← must have inventory-failed consumer added
6. Inventory Service   (port 8084)  ← this service
7. Notification Service (port 8085)
8. API Gateway         (port 8080)
```

---

## ✅ Implementation Checklist

### 🔴 Must Build (Core)
- [ ] `InventoryServiceApplication.java` with `@EnableDiscoveryClient`
- [ ] `Inventory.java` entity with `@Version`
- [ ] `ProcessedEvent.java` entity (idempotency)
- [ ] `InventoryRepository.java` with `findByProductId(Long)`
- [ ] `ProcessedEventRepository.java` with `existsById(String)`
- [ ] `KafkaConfig.java` with manual ack + topic declarations
- [ ] `OrderEventConsumer.java` with `@KafkaListener`
- [ ] `InventoryServiceImpl.java` with two-layer locking
- [ ] `InventoryEventProducer.java`
- [ ] `GatewayAuthFilter.java` + `SecurityConfig.java`
- [ ] `application.properties` with all required configs
- [ ] `inventory_db` MySQL database created

### 🔴 Must Update (Other Services)
- [ ] Order Service: Add `userEmail` to `OrderEvent`
- [ ] Order Service: Create `InventoryFailedConsumer.java`
- [ ] Order Service: Add `InventoryFailedEvent.java` + `OrderStatusEvent.java` DTOs
- [ ] Order Service: Add Kafka consumer properties to `application.properties`
- [ ] API Gateway: Add inventory routes to `FilterConfig.java`

### 🟡 Should Build (Admin Operations)
- [ ] `InventoryController.java` (init, restock, list)
- [ ] `GlobalExceptionHandler.java`
- [ ] `InitInventoryRequest.java` + `RestockRequest.java` DTOs

