# Notification Service - Microservice Documentation

> **Last Updated:** June 24, 2026  
> **Version:** 0.0.2-SNAPSHOT  
> **Port:** `8085`  
> **Service Name:** `NOTIFICATION-SERVICE`

---

## 📋 Overview

| Property | Value |
|----------|-------|
| **Service Name** | `NOTIFICATION-SERVICE` |
| **Port** | `8085` |
| **Database** | MySQL (`notification_db`) |
| **Message Broker** | Apache Kafka (Consumer ONLY) |
| **Kafka Topic** | `order-status-updated` (3 partitions) |
| **Consumer Group** | `notification-service-group` |
| **Registry** | Eureka Server (`http://localhost:8761/eureka`) |
| **Role** | Async event-driven consumer — NOT directly called by other services for event flow |

> ⚠️ **IMPORTANT:** Notification Service does **NOT** consume `order-events`.  
> It consumes `order-status-updated` which is published by **Inventory Service** (on success → CONFIRMED)  
> and **Order Service** (on Saga failure → FAILED).

---

## 🏗️ Role in Architecture — Choreography-Based Saga

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                     COMPLETE EVENT FLOW (Choreography-Based Saga)                         │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                          │
│  ┌─────────────────────┐                                                                │
│  │     Order Service   │                                                                │
│  │     (:8083)         │                                                                │
│  └──────────┬──────────┘                                                                │
│             │                                                                            │
│             │ (1) Publish OrderEvent (status=PLACED)                                     │
│             ▼                                                                            │
│  ┌───────────────────────────────────────────────────┐                                  │
│  │         Kafka Topic: order-events (3 partitions)  │                                  │
│  └──────────────────────────┬────────────────────────┘                                  │
│                             │                                                            │
│                             │ (2) Consumed by                                            │
│                             ▼                                                            │
│  ┌──────────────────────────────────────────┐                                           │
│  │         Inventory Service (:8084)        │                                           │
│  │                                          │                                           │
│  │  • Check stock availability              │                                           │
│  │  • @Version optimistic lock (same JVM)   │                                           │
│  │  • Redis SETNX distributed lock (cross)  │                                           │
│  │  • Idempotency log (ProcessedEvent)      │                                           │
│  └─────────┬───────────────────────┬────────┘                                           │
│            │                       │                                                     │
│     ✅ Stock OK              ❌ Stock FAILED                                             │
│            │                       │                                                     │
│  (3a) Publish CONFIRMED     (3b) Publish InventoryFailedEvent                           │
│       to order-status-            to inventory-failed                                    │
│       updated                      │                                                     │
│            │                       │                                                     │
│            │              ┌────────▼────────────────────┐                               │
│            │              │     Order Service (:8083)   │                               │
│            │              │  [SAGA COMPENSATION]        │                               │
│            │              │  • Sets order status=FAILED │                               │
│            │              │  • Compensating transaction │                               │
│            │              └────────┬────────────────────┘                               │
│            │                       │                                                     │
│            │              (4) Publish FAILED                                             │
│            │                  to order-status-updated                                    │
│            │                       │                                                     │
│            └───────────┬───────────┘                                                     │
│                        ▼                                                                 │
│  ┌──────────────────────────────────────────────────────────┐                          │
│  │     Kafka Topic: order-status-updated (3 partitions)      │                          │
│  └────────────────────────────────┬──────────────────────────┘                          │
│                                   │                                                      │
│                                   │ (5) Consumed by                                      │
│                                   ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐                           │
│  │         NOTIFICATION SERVICE (this service) (:8085)      │                           │
│  │                                                          │                           │
│  │  • Receives CONFIRMED → Send "Order Confirmed" email    │                           │
│  │  • Receives FAILED    → Send "Order Failed" email       │                           │
│  │  • Receives SHIPPED   → Send "Order Shipped" email      │                           │
│  │  • Receives DELIVERED → Send "Order Delivered" email    │                           │
│  │  • Logs to notification_db (MySQL)                       │                           │
│  └──────────────────────────────────────────────────────────┘                           │
│                                                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### Why This Design?

| Property | Explanation |
|----------|-------------|
| **ASYNCHRONOUS** | Order → Inventory → Notification via Kafka — background operations, decoupled |
| **RESILIENT** | If Notification Service is down, Kafka retains messages. No data loss. |
| **DECOUPLED** | Notification doesn't know about Order/Inventory internals — just reads `order-status-updated` |
| **PARALLEL** | 3 partitions per topic → 3 service instances can process concurrently |
| **SAGA PATTERN** | Choreography-based — each service reacts to events, no central orchestrator |

---

## 📦 Dependencies — `pom.xml`

```xml
<dependencies>

    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring Data JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- MySQL Driver -->
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

    <!-- Eureka Client -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>

    <!-- Spring Security (for GatewayAuthFilter on REST endpoints) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- Spring Mail (for email notifications) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-mail</artifactId>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Spring Boot Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Validation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

</dependencies>
```

---

## ⚙️ Configuration — `application.properties`

```properties
# ================= APPLICATION =================
spring.application.name=NOTIFICATION-SERVICE
server.port=8085

# ================= EUREKA =================
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.instance.prefer-ip-address=true
eureka.client.fetch-registry=true
eureka.client.register-with-eureka=true

# ================= MYSQL =================
spring.datasource.url=jdbc:mysql://localhost:3306/notification_db?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=root
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect

# ================= KAFKA CONSUMER =================
# Consumes from: order-status-updated (NOT order-events!)
# Published by: Inventory Service (CONFIRMED) + Order Service (FAILED)
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=notification-service-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=*
spring.kafka.consumer.properties.spring.json.value.default.type=com.onlineshopping.notification_service.event.OrderStatusEvent

# ================= SMTP (Gmail example) =================
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=${SMTP_USERNAME:your-email@gmail.com}
spring.mail.password=${SMTP_PASSWORD:your-app-password}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true

# ================= ACTUATOR =================
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always

# ================= LOGGING =================
logging.level.com.onlineshopping.notification_service=DEBUG
logging.level.org.springframework.kafka=INFO
```

> ⚠️ **SMTP credentials** must be stored in environment variables or Spring Cloud Config Server — never hardcoded.

---

## 📁 Project Structure

```
notification-service/
└── src/main/java/com/onlineshopping/notification_service/
    ├── NotificationServiceApplication.java         # @SpringBootApplication @EnableDiscoveryClient
    │
    ├── config/
    │   └── KafkaConsumerConfig.java                # Manual-ack consumer factory, topic declaration
    │
    ├── consumer/
    │   └── OrderStatusEventConsumer.java           # @KafkaListener on "order-status-updated"
    │
    ├── entity/
    │   └── NotificationLog.java                    # MySQL entity — notification history
    │
    ├── event/
    │   ├── OrderStatusEvent.java                   # Kafka message DTO (from Inventory/Order Service)
    │   └── OrderItemEvent.java                     # Embedded item DTO
    │
    ├── repository/
    │   └── NotificationLogRepository.java
    │
    ├── security/
    │   ├── GatewayAuthFilter.java                  # Reads X-User-* headers from API Gateway
    │   └── SecurityConfig.java
    │
    ├── service/
    │   ├── NotificationService.java                # Interface
    │   └── NotificationServiceImpl.java            # Email dispatch + DB log
    │
    └── controller/
        └── NotificationController.java             # REST: query notification history
```

---

## 📨 Kafka Event Schema — `OrderStatusEvent`

This is the **incoming event** consumed from `order-status-updated`.  
It is published by:
- **Inventory Service** → `status = "CONFIRMED"` (stock reserved successfully)
- **Order Service** → `status = "FAILED"` (Saga compensation after inventory failure)
- **Order Service** → `status = "SHIPPED"`, `"DELIVERED"` (future status updates)

```java
package com.onlineshopping.notification_service.event;

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

    private String orderId;          // MongoDB Order ID from Order Service
    private Long userId;
    private String userEmail;        // REQUIRED — used for sending email (no User Service call needed)
    private String status;           // CONFIRMED, FAILED, SHIPPED, DELIVERED, CANCELLED
    private Double totalAmount;
    private List<OrderItemEvent> items;
    private String failureReason;    // null on success, message on failure (e.g., "Insufficient stock")
    private LocalDateTime timestamp;
}
```

### `OrderItemEvent.java`

```java
package com.onlineshopping.notification_service.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemEvent {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
```

> ⚠️ **SCHEMA MUST MATCH:** These fields must exactly match what Inventory Service publishes  
> (`OrderStatusEvent` in `com.onlineshopping.inventory_service.dto`).  
> Configure `spring.json.trusted.packages=*` to avoid deserialization errors.

---

## ⚙️ Kafka Configuration — `KafkaConsumerConfig.java`

```java
package com.onlineshopping.notification_service.config;

import com.onlineshopping.notification_service.event.OrderStatusEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    /**
     * Manual-ack consumer factory.
     * Offset committed ONLY after successful notification dispatch + DB log.
     * If email fails and exception propagates → Kafka redelivers on restart.
     */
    @Bean
    public ConsumerFactory<String, OrderStatusEvent> orderStatusConsumerFactory(KafkaProperties kp) {
        Map<String, Object> props = new HashMap<>(kp.buildConsumerProperties(null));
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE,
                  "com.onlineshopping.notification_service.event.OrderStatusEvent");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderStatusEvent>
            kafkaListenerContainerFactory(ConsumerFactory<String, OrderStatusEvent> cf) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, OrderStatusEvent>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // 3 concurrent consumers → matches 3 partitions for max parallelism
        factory.setConcurrency(3);
        return factory;
    }
}
```

---

## 📡 Kafka Consumer — `OrderStatusEventConsumer.java`

```java
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
```

---

## 🛎️ Service Layer — `NotificationServiceImpl.java`

```java
package com.onlineshopping.notification_service.service;

import com.onlineshopping.notification_service.entity.NotificationLog;
import com.onlineshopping.notification_service.event.OrderStatusEvent;
import com.onlineshopping.notification_service.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final JavaMailSender mailSender;
    private final NotificationLogRepository notificationLogRepository;

    @Override
    public void processStatusNotification(OrderStatusEvent event) {
        // 1. Send appropriate email based on status
        sendStatusEmail(event);

        // 2. Log notification to DB
        NotificationLog logEntry = NotificationLog.builder()
                .orderId(event.getOrderId())
                .userId(event.getUserId())
                .recipientEmail(event.getUserEmail())
                .type("ORDER_" + event.getStatus())     // ORDER_CONFIRMED, ORDER_FAILED, etc.
                .status("SENT")
                .message(buildLogMessage(event))
                .createdAt(LocalDateTime.now())
                .build();

        notificationLogRepository.save(logEntry);
        log.info("[NOTIFICATION] Logged: type={}, orderId={}", logEntry.getType(), event.getOrderId());
    }

    private void sendStatusEmail(OrderStatusEvent event) {
        if (event.getUserEmail() == null || event.getUserEmail().isBlank()) {
            log.warn("[EMAIL] No email for userId={}, skipping. orderId={}",
                    event.getUserId(), event.getOrderId());
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(event.getUserEmail());
        message.setSubject(buildSubject(event));
        message.setText(buildEmailBody(event));

        mailSender.send(message);
        log.info("[EMAIL] Sent {} notification to {} for orderId={}",
                event.getStatus(), event.getUserEmail(), event.getOrderId());
    }

    private String buildSubject(OrderStatusEvent event) {
        return switch (event.getStatus()) {
            case "CONFIRMED" -> "✅ Order Confirmed - #" + event.getOrderId();
            case "FAILED"    -> "❌ Order Failed - #" + event.getOrderId();
            case "SHIPPED"   -> "🚚 Order Shipped - #" + event.getOrderId();
            case "DELIVERED"  -> "📦 Order Delivered - #" + event.getOrderId();
            case "CANCELLED" -> "🚫 Order Cancelled - #" + event.getOrderId();
            default          -> "📋 Order Update - #" + event.getOrderId();
        };
    }

    private String buildEmailBody(OrderStatusEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello,\n\n");

        switch (event.getStatus()) {
            case "CONFIRMED" -> {
                sb.append("Your order has been CONFIRMED! Stock has been reserved.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Total      : ₹").append(event.getTotalAmount()).append("\n");
            }
            case "FAILED" -> {
                sb.append("Unfortunately, your order could NOT be processed.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Reason     : ").append(event.getFailureReason()).append("\n");
                sb.append("\nPlease try again or contact support.\n");
            }
            case "SHIPPED" -> {
                sb.append("Your order has been SHIPPED!\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Total      : ₹").append(event.getTotalAmount()).append("\n");
            }
            case "DELIVERED" -> {
                sb.append("Your order has been DELIVERED!\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
            }
            case "CANCELLED" -> {
                sb.append("Your order has been CANCELLED.\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
                sb.append("Reason     : ").append(event.getFailureReason() != null ?
                        event.getFailureReason() : "Cancelled by user").append("\n");
            }
            default -> {
                sb.append("Your order status has been updated to: ").append(event.getStatus()).append("\n\n");
                sb.append("Order ID   : ").append(event.getOrderId()).append("\n");
            }
        }

        sb.append("\nTimestamp  : ").append(event.getTimestamp()).append("\n");

        if (event.getItems() != null && !event.getItems().isEmpty()) {
            sb.append("\nItems Ordered:\n");
            event.getItems().forEach(item ->
                sb.append("  - ").append(item.getProductName())
                  .append(" x").append(item.getQuantity())
                  .append(" @ ₹").append(item.getPrice()).append("\n")
            );
        }

        sb.append("\nThank you for shopping with us!\n");
        sb.append("Team Online Shopping");
        return sb.toString();
    }

    private String buildLogMessage(OrderStatusEvent event) {
        return switch (event.getStatus()) {
            case "CONFIRMED" -> "Order confirmed, stock reserved. Order #" + event.getOrderId();
            case "FAILED"    -> "Order failed: " + event.getFailureReason() + ". Order #" + event.getOrderId();
            case "SHIPPED"   -> "Order shipped. Order #" + event.getOrderId();
            case "DELIVERED"  -> "Order delivered. Order #" + event.getOrderId();
            case "CANCELLED" -> "Order cancelled. Order #" + event.getOrderId();
            default          -> "Status update: " + event.getStatus() + ". Order #" + event.getOrderId();
        };
    }
}
```

### `NotificationService.java` — Interface

```java
package com.onlineshopping.notification_service.service;

import com.onlineshopping.notification_service.event.OrderStatusEvent;

public interface NotificationService {
    void processStatusNotification(OrderStatusEvent event);
}
```

---

## 🗄️ Entity — `NotificationLog`

```java
package com.onlineshopping.notification_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "notification_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String orderId;          // MongoDB Order ID

    @Column(nullable = false)
    private Long userId;

    private String recipientEmail;

    @Column(nullable = false)
    private String type;             // ORDER_CONFIRMED, ORDER_FAILED, ORDER_SHIPPED, etc.

    @Column(nullable = false)
    private String status;           // SENT, FAILED, PENDING

    @Column(length = 500)
    private String message;

    private LocalDateTime createdAt;
}
```

### `NotificationLogRepository.java`

```java
package com.onlineshopping.notification_service.repository;

import com.onlineshopping.notification_service.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    List<NotificationLog> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<NotificationLog> findByOrderId(String orderId);
    List<NotificationLog> findByType(String type);
    boolean existsByOrderIdAndType(String orderId, String type);  // For idempotency check
}
```

---

## 🔐 Security — `GatewayAuthFilter` + `SecurityConfig`

Notification Service follows the **same gateway-trust pattern** as Product, Order, and Inventory services.  
JWT validation is done at the API Gateway — this service only reads forwarded headers.

### `GatewayAuthFilter.java`

```java
package com.onlineshopping.notification_service.security;

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
import java.util.Collections;
import java.util.List;

@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID   = "X-User-Id";
    private static final String HEADER_USERNAME  = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String userId   = request.getHeader(HEADER_USER_ID);
        String username = request.getHeader(HEADER_USERNAME);
        String role     = request.getHeader(HEADER_USER_ROLE);

        if (username != null && !username.isBlank()) {
            List<SimpleGrantedAuthority> authorities = Collections.emptyList();
            if (role != null && !role.isBlank()) {
                String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                authorities = List.of(new SimpleGrantedAuthority(roleWithPrefix));
            }
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }
}
```

### `SecurityConfig.java`

```java
package com.onlineshopping.notification_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
```

---

## 🌐 REST Controller — `NotificationController.java`

Optional REST API to query notification history (admin panel, user notification inbox).

```java
package com.onlineshopping.notification_service.controller;

import com.onlineshopping.notification_service.entity.NotificationLog;
import com.onlineshopping.notification_service.repository.NotificationLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;

    /** Get all notifications for the authenticated user (reads X-User-Id from Gateway) */
    @GetMapping("/my")
    public ResponseEntity<List<NotificationLog>> getMyNotifications(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        if (userIdStr == null || userIdStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = Long.parseLong(userIdStr);
        return ResponseEntity.ok(notificationLogRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    /** Get all notifications for a specific order (admin use) */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<NotificationLog>> getByOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(notificationLogRepository.findByOrderId(orderId));
    }
}
```

---

## 🔗 API Gateway Route — Add to `application.properties`

```properties
# ================= NOTIFICATION SERVICE =================
spring.cloud.gateway.routes[5].id=notification-service
spring.cloud.gateway.routes[5].uri=lb://NOTIFICATION-SERVICE
spring.cloud.gateway.routes[5].predicates[0]=Path=/api/notifications/**
spring.cloud.gateway.routes[5].filters[0]=RewritePath=/api/notifications/(?<segment>.*), /notifications/${segment}
```

> This only routes the REST history endpoints.  
> The Kafka consumer runs independently — it does **NOT** go through the API Gateway.

---

## 🔗 Relationship With Other Services

### What Notification Service RECEIVES (via Kafka)

| Topic | Published By | Status | Meaning |
|-------|-------------|--------|---------|
| `order-status-updated` | Inventory Service | `CONFIRMED` | Stock reserved successfully |
| `order-status-updated` | Order Service (Saga) | `FAILED` | Inventory check failed, order cancelled |
| `order-status-updated` | Order Service | `SHIPPED` | Admin shipped the order |
| `order-status-updated` | Order Service | `DELIVERED` | Order delivered |
| `order-status-updated` | Order Service | `CANCELLED` | User/admin cancelled order |

### What Notification Service Does NOT Do

- ❌ Does NOT consume `order-events` (that's for Inventory Service)
- ❌ Does NOT call User Service for email (email included in Kafka event)
- ❌ Does NOT produce any Kafka events (terminal consumer — end of chain)
- ❌ Does NOT validate JWT tokens (trusts API Gateway headers)

---

## 🔁 Complete Saga Flow — Happy Path

```
1. Client → POST /api/orders/newOrder (via API Gateway)
2. Order Service:
   - Validates user (Feign → User Service)
   - Validates products (Feign → Product Service)
   - Saves order to MongoDB (status=PLACED)
   - Publishes OrderEvent to Kafka topic: order-events
   
3. Inventory Service (consumes order-events):
   - Redis SETNX distributed lock per product
   - @Version optimistic lock on Inventory entity
   - Decrements stock
   - Saves idempotency log (ProcessedEvent)
   - Publishes OrderStatusEvent(status=CONFIRMED) to: order-status-updated
   - Releases Redis lock
   
4. Notification Service (consumes order-status-updated):    ← THIS SERVICE
   - Receives CONFIRMED event
   - Sends "Order Confirmed" email to userEmail
   - Logs notification to notification_db
```

## 🔁 Complete Saga Flow — Failure Path (Insufficient Stock)

```
1. Client → POST /api/orders/newOrder
2. Order Service → saves order (status=PLACED), publishes to order-events
3. Inventory Service (consumes order-events):
   - Checks stock → INSUFFICIENT
   - Saves ProcessedEvent(result=FAILED) — idempotency
   - Publishes InventoryFailedEvent to: inventory-failed
   
4. Order Service (consumes inventory-failed):               ← SAGA COMPENSATION
   - Reads InventoryFailedEvent
   - Sets order status = FAILED (compensating transaction)
   - Publishes OrderStatusEvent(status=FAILED, failureReason=...) to: order-status-updated
   
5. Notification Service (consumes order-status-updated):    ← THIS SERVICE
   - Receives FAILED event
   - Sends "Order Failed" email with failure reason
   - Logs notification to notification_db
```

---

## 📋 Missing / TODO Checklist

### 🔴 Critical — Must Implement Before Service Works

- [ ] **Create `notification_db`** in MySQL:
  ```sql
  CREATE DATABASE IF NOT EXISTS notification_db;
  ```
- [ ] **Create `NotificationServiceApplication.java`**:
  ```java
  @SpringBootApplication
  @EnableDiscoveryClient
  public class NotificationServiceApplication {
      public static void main(String[] args) {
          SpringApplication.run(NotificationServiceApplication.class, args);
      }
  }
  ```
- [ ] **Verify `OrderStatusEvent` schema** matches Inventory Service's published event exactly
- [ ] **Configure SMTP** credentials (use environment variables `${SMTP_USERNAME}`, `${SMTP_PASSWORD}`)
- [ ] **Ensure `userEmail` is present** in the Kafka event — Order Service must include it when publishing to `order-events`, and Inventory Service must forward it to `order-status-updated`

### 🟡 Important — Should Implement

- [ ] **Dead-Letter Topic (DLT):** Route failed notifications to `order-status-updated.DLT`
  ```java
  @KafkaListener(topics = "order-status-updated.DLT", groupId = "notification-dlt-group")
  public void consumeDeadLetter(OrderStatusEvent event) {
      log.error("[DLT] Notification permanently failed: orderId={}", event.getOrderId());
      // Alert ops team
  }
  ```
- [ ] **Idempotency:** Don't send duplicate email if same `orderId+status` combo arrives twice
  ```java
  // Before sending email:
  if (notificationLogRepository.existsByOrderIdAndType(orderId, "ORDER_" + status)) {
      log.info("[IDEMPOTENT] Already sent for orderId={}, status={}", orderId, status);
      ack.acknowledge();
      return;
  }
  ```
- [ ] **Retry mechanism:** Use `@RetryableTopic` or Spring Retry for transient SMTP failures

### 🟢 Nice to Have

- [ ] **HTML email templates** using Thymeleaf (`spring-boot-starter-thymeleaf`)
- [ ] **SMS / Push notifications** via Twilio / Firebase — add separate channels
- [ ] **User notification preferences** — DB table for opt-in/out per notification type
- [ ] **Notification inbox REST API** with pagination
- [ ] **Distributed tracing** with Micrometer/Zipkin to trace Kafka → email latency
- [ ] **Metrics:** Track email send success/failure rate via Micrometer

---

## 🗺️ Complete Updated Architecture — All Services

```
┌──────────────────────────────────────────────────────────────────────┐
│                    SYNCHRONOUS (REST/Feign)                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  API Gateway (:8080)                                                 │
│       │                                                              │
│       ├── /api/auth/**            → USER-SERVICE          (:8081)    │
│       ├── /api/users/**           → USER-SERVICE          (:8081)    │
│       ├── /api/products/**        → PRODUCT-SERVICE       (:8082)    │
│       ├── /api/orders/**          → ORDER-SERVICE         (:8083)    │
│       ├── /api/inventory/**       → INVENTORY-SERVICE     (:8084)    │
│       └── /api/notifications/**   → NOTIFICATION-SERVICE  (:8085)    │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                    ASYNCHRONOUS (Kafka)                               │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Order Service (:8083)                                               │
│       │                                                              │
│       │ publish → order-events (3 partitions)                        │
│       │                                                              │
│       ▼                                                              │
│  Inventory Service (:8084)                                           │
│       │                                                              │
│       ├─ SUCCESS → publish CONFIRMED → order-status-updated          │
│       └─ FAIL    → publish → inventory-failed                        │
│                                    │                                 │
│                                    ▼                                 │
│                            Order Service (:8083)                      │
│                            [SAGA compensation]                       │
│                            status = FAILED                           │
│                                    │                                 │
│                                    │ publish FAILED →                 │
│                                    │ order-status-updated             │
│                                    │                                 │
│                    ┌───────────────┘                                  │
│                    ▼                                                  │
│  Notification Service (:8085)  ← consumes order-status-updated       │
│       │                                                              │
│       ├── Send CONFIRMED email                                       │
│       ├── Send FAILED email (with reason)                            │
│       ├── Send SHIPPED / DELIVERED / CANCELLED emails                │
│       └── Log to notification_db                                     │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### Kafka Topics Summary

| Topic | Partitions | Producer | Consumer | Purpose |
|-------|-----------|----------|----------|---------|
| `order-events` | 3 | Order Service | Inventory Service | New order → check/reserve stock |
| `order-status-updated` | 3 | Inventory Service + Order Service | **Notification Service** | All status changes → email/SMS |
| `inventory-failed` | 3 | Inventory Service | Order Service | Saga compensation trigger |

### Race Condition Defense (in Inventory Service)

| Layer | Mechanism | Scope | Handles |
|-------|-----------|-------|---------|
| **Primary** | `@Version` (JPA optimistic lock) | Same JVM instance | Two threads updating same row |
| **Fallback** | Redis `SETNX` (distributed lock) | Cross-instance (multiple JVMs) | Two instances decrementing same product |
| **Safety** | Lock TTL = 10s | Auto-recovery | Service crash mid-lock → auto-expires |

---

## 🧪 Postman Test Endpoints

> **Base URL:** `http://localhost:8080` (always via API Gateway)

| Method | URL | Auth | Description |
|--------|-----|------|-------------|
| GET | `/api/notifications/my` | Bearer Token | Get my notification history |
| GET | `/api/notifications/order/{orderId}` | Bearer Token | Get logs for a specific order |

> **Kafka events are NOT triggered via Postman** — they fire automatically when:
> 1. `POST /api/orders/newOrder` → Order Service publishes to `order-events`
> 2. Inventory Service processes → publishes to `order-status-updated`
> 3. Notification Service consumes and sends email

### Testing End-to-End Notification Flow

```
Step 1: Ensure Kafka + MySQL + all services are running
Step 2: Initialize inventory (ADMIN token):
  POST http://localhost:8080/api/inventory/init
  { "productId": 1, "productName": "Laptop", "quantity": 10 }

Step 3: Place an order (USER token):
  POST http://localhost:8080/api/orders/newOrder
  { "userId": 1, "items": [{ "productId": 1, "quantity": 2 }] }

Step 4: Check notification was sent:
  GET http://localhost:8080/api/notifications/my
  → Should show ORDER_CONFIRMED entry

Step 5: Test failure — place order with quantity > stock:
  POST http://localhost:8080/api/orders/newOrder
  { "userId": 1, "items": [{ "productId": 1, "quantity": 9999 }] }

Step 6: Check failure notification:
  GET http://localhost:8080/api/notifications/my
  → Should show ORDER_FAILED entry with failure reason
```

---

## ✅ Implementation Order

1. Create Spring Boot project `notification-service` (port `8085`)
2. Add dependencies to `pom.xml` (Kafka, JPA, Mail, Eureka, Security)
3. Configure `application.properties`
4. Create `OrderStatusEvent.java` + `OrderItemEvent.java` (match Inventory Service schema)
5. Create `NotificationLog.java` entity + repository
6. Create `KafkaConsumerConfig.java` (manual-ack, 3 concurrency)
7. Create `NotificationServiceImpl.java` (email + log per status)
8. Create `OrderStatusEventConsumer.java` (Kafka `@KafkaListener` on `order-status-updated`)
9. Create `GatewayAuthFilter.java` + `SecurityConfig.java`
10. Create `NotificationController.java` (REST history)
11. Add Gateway route for `/api/notifications/**`
12. Create `notification_db` in MySQL
13. Register in Eureka — verify at `http://localhost:8761`
14. Test end-to-end: place order → check email + DB log

---

## 📌 Key Differences from Previous Version (v0.0.1)

| Aspect | OLD (v0.0.1) | NEW (v0.0.2) — CORRECT |
|--------|--------------|------------------------|
| **Topic consumed** | `order-events` | `order-status-updated` ✅ |
| **Port** | `8084` | `8085` (8084 = Inventory) ✅ |
| **Event schema** | `OrderPlacedEvent` | `OrderStatusEvent` ✅ |
| **Handles statuses** | Only `ORDER_PLACED` | ALL: CONFIRMED, FAILED, SHIPPED, DELIVERED, CANCELLED ✅ |
| **Email for failures** | Not handled | Sends failure email with reason ✅ |
| **Saga awareness** | None | Understands compensating transactions ✅ |
| **Ack mode** | Auto | Manual (reliability guarantee) ✅ |
| **Concurrency** | Default (1) | 3 (matches topic partitions) ✅ |
| **`order-events` consumer** | This service | Inventory Service ✅ |
