# Order Service (`order-service`)

Order management microservice for the **Online Shopping** system.  
It validates user/product references via downstream services, stores orders in MongoDB, and publishes order events to Kafka.

---

## ⚠️ REQUIRED CHANGES - Integration Checklist

> **Last Updated:** April 22, 2026  
> **Status:** Requires modifications for full API Gateway integration

### 🔴 CRITICAL - Must Implement

#### 1. Add Security Configuration (Missing)

**Issue:** No `SecurityFilterChain` configured. Spring Security defaults to Basic Auth.

**Create File:** `src/main/java/com/onlineshopping/order_service/security/SecurityConfig.java`

```java
package com.onlineshopping.order_service.security;

import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private GatewayAuthFilter gatewayAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

#### 2. Add Gateway Auth Filter (Missing)

**Issue:** Service doesn't read `X-User-Id`, `X-Username`, `X-User-Role` headers from API Gateway.

**Create File:** `src/main/java/com/onlineshopping/order_service/security/GatewayAuthFilter.java`

```java
package com.onlineshopping.order_service.security;

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
import java.util.Map;

@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String userId = request.getHeader(HEADER_USER_ID);
        String username = request.getHeader(HEADER_USERNAME);
        String role = request.getHeader(HEADER_USER_ROLE);

        if (username != null && !username.isEmpty()) {
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (role != null && !role.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
            
            // Store userId in details for access in controllers
            auth.setDetails(Map.of(
                "userId", userId != null ? userId : "",
                "username", username,
                "role", role != null ? role : "USER"
            ));

            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
```

#### 3. Fix JWT Secret (Mismatch)

**Issue:** JWT secret differs from API Gateway. Must match for token validation consistency.

**Update:** `src/main/resources/application.properties`

```properties
# CHANGE THIS:
# jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZA==

# TO THIS (must match API Gateway):
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

#### 4. Fix UserResponse DTO Field — Keep `name` (NOT `username`)

**Issue (README was previously wrong):** Order Service `UserResponse` should use `name` field.
User Service `AuthController.getUserById()` already maps `username → name`:

```java
// AuthController.java (User Service) — actual implementation:
return ResponseEntity.ok(Map.of(
        "id", user.getId(),
        "name", user.getUsername(),   // ← mapped to "name" ✅
        "email", user.getEmail()
));
```

**Correct `UserResponse.java` in Order Service:**
```java
package com.onlineshopping.order_service.dto;

import lombok.Data;

@Data
public class UserResponse {
    private Long id;
    private String name;    // ✅ matches User Service response field "name"
    private String email;
}
```

> ⚠️ Do NOT change to `username` — User Service returns `name` field.

#### 5. Fix `getUserOrders` Return Type

**Issue:** `OrderController.getUserOrders()` returns `ResponseEntity<OrderResponse>` (single),
but a user can have many orders. Should return `List<OrderResponse>`.

**Current (wrong):**
```java
@GetMapping("/user/{userId}")
public ResponseEntity<OrderResponse> getUserOrders(@PathVariable Long userId) {
    return ResponseEntity.ok(orderService.getUserOrders(userId));
}
```

**Fix:**
```java
import java.util.List;

@GetMapping("/user/{userId}")
public ResponseEntity<List<OrderResponse>> getUserOrders(@PathVariable Long userId) {
    return ResponseEntity.ok(orderService.getUserOrders(userId));
}
```

**Also update `OrderService` interface and `OrderServiceImpl`:**
```java
// OrderService.java
List<OrderResponse> getUserOrders(Long userId);

// OrderServiceImpl.java
@Override
public List<OrderResponse> getUserOrders(Long userId) {
    return orderRepository.findByUserId(userId)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
}
```



#### 5. Add Feign Header Propagation

**Issue:** When Order Service calls User/Product Service via Feign, it should forward the user headers.

**Create File:** `src/main/java/com/onlineshopping/order_service/config/FeignConfig.java`

```java
package com.onlineshopping.order_service.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                
                String userId = request.getHeader("X-User-Id");
                String username = request.getHeader("X-Username");
                String role = request.getHeader("X-User-Role");

                if (userId != null) requestTemplate.header("X-User-Id", userId);
                if (username != null) requestTemplate.header("X-Username", username);
                if (role != null) requestTemplate.header("X-User-Role", role);
            }
        };
    }
}
```

#### 6. Add Redis Caching (Not Implemented)

**Issue:** No caching for orders. Repeated calls hit MongoDB every time.

**Add Dependency:** `pom.xml`
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**Add Configuration:** `application.properties`
```properties
# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms
```

**Create File:** `src/main/java/com/onlineshopping/order_service/config/RedisConfig.java`
```java
package com.onlineshopping.order_service.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .build();
    }
}
```

**Update Service:** Add `@Cacheable` annotations
```java
@Cacheable(value = "orders", key = "#id")
public OrderResponse getOrderById(String id) { ... }

@CacheEvict(value = "orders", key = "#id")
public void updateOrderStatus(String id, String status) { ... }
```

#### 7. Configure Circuit Breaker (Exists but not configured)

**Issue:** Resilience4j dependency exists but no configuration.

**Add to:** `application.properties`
```properties
# Circuit Breaker - User Service
resilience4j.circuitbreaker.instances.userService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.userService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.userService.waitDurationInOpenState=10000
resilience4j.circuitbreaker.instances.userService.permittedNumberOfCallsInHalfOpenState=3

# Circuit Breaker - Product Service
resilience4j.circuitbreaker.instances.productService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.productService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.productService.waitDurationInOpenState=10000
```

**Update Service Implementation:**
```java
@CircuitBreaker(name = "userService", fallbackMethod = "userFallback")
public UserResponse validateUser(Long userId) {
    return userClient.getUserById(userId);
}

public UserResponse userFallback(Long userId, Exception e) {
    log.error("User service unavailable for userId: {}", userId);
    throw new ServiceUnavailableException("User service temporarily unavailable");
}

@CircuitBreaker(name = "productService", fallbackMethod = "productFallback")
public ProductResponse validateProduct(Long productId) {
    return productClient.getProductById(productId);
}

public ProductResponse productFallback(Long productId, Exception e) {
    log.error("Product service unavailable for productId: {}", productId);
    throw new ServiceUnavailableException("Product service temporarily unavailable");
}
```

---

### 🟢 NICE TO HAVE - Optional Improvements

#### 8. Add Global Exception Handler

**Create File:** `src/main/java/com/onlineshopping/order_service/exception/GlobalExceptionHandler.java`

```java
package com.onlineshopping.order_service.exception;

import feign.FeignException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 500,
            "error", "Internal Server Error",
            "message", e.getMessage()
        ));
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Map<String, Object>> handleFeign(FeignException e) {
        return ResponseEntity.status(e.status()).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", e.status(),
            "error", "Service Communication Error",
            "message", "Downstream service unavailable: " + e.getMessage()
        ));
    }
}
```

#### 9. Remove Unused JwtUtil

**Issue:** `JwtUtil.java` exists but JWT validation is done at API Gateway. Can be removed.

**Action:** Delete or deprecate `src/main/java/com/onlineshopping/order_service/security/JwtUtil.java`

---

### 📋 Updated Project Structure (After Changes)

```
order-service/
├── src/main/java/com/onlineshopping/order_service/
│   ├── OrderServiceApplication.java
│   ├── client/
│   │   ├── ProductClient.java
│   │   └── UserClient.java
│   ├── config/                          # NEW
│   │   ├── FeignConfig.java             # NEW - Header propagation
│   │   └── RedisConfig.java             # NEW - Caching
│   ├── controller/
│   │   └── OrderController.java
│   ├── dto/
│   │   ├── UserResponse.java            # MODIFIED - field name fix
│   │   └── ... (other DTOs)
│   ├── exception/                       # NEW
│   │   └── GlobalExceptionHandler.java  # NEW
│   ├── security/
│   │   ├── GatewayAuthFilter.java       # NEW - Read gateway headers
│   │   ├── SecurityConfig.java          # NEW - Security filter chain
│   │   └── JwtUtil.java                 # DEPRECATED - Remove
│   └── service/
│       └── OrderServiceImpl.java        # MODIFIED - Add caching + circuit breaker
└── src/main/resources/
    └── application.properties           # MODIFIED - JWT secret + Redis + Resilience4j
```

---

## 1) Service Overview

| Property            | Value                        |
|---------------------|------------------------------|
| **Service Name**    | `order-service`              |
| **Port**            | `8083`                       |
| **Database**        | MongoDB (`orderdb`)          |
| **Discovery**       | Eureka                       |
| **Messaging**       | Kafka (producer)             |
| **Java Version**    | 21                           |
| **Spring Boot**     | 3.2.5                        |
| **Spring Cloud**    | 2023.0.1                     |
| **Base Package**    | `com.onlineshopping.order_service` |

### Main Responsibilities
- Create orders (with user & product validation from other services)
- Fetch order by ID
- Fetch latest order of a user
- Update order status
- Publish order-created events to Kafka topic `order-events`

---

## 2) Project Structure

```
order-service/
├── src/main/java/com/onlineshopping/order_service/
│   ├── OrderServiceApplication.java      # Boot entry point (@EnableFeignClients, @EnableDiscoveryClient)
│   ├── client/
│   │   ├── ProductClient.java            # Feign client → product-service
│   │   └── UserClient.java              # Feign client → user-service
│   ├── controller/
│   │   └── OrderController.java          # REST endpoints
│   ├── dto/
│   │   ├── NewOrder.java                 # Create-order request DTO
│   │   ├── OrderEvent.java               # Kafka event payload
│   │   ├── OrderItemRequest.java         # Item request DTO
│   │   ├── OrderResponse.java            # Order response DTO
│   │   ├── ProductResponse.java          # Product data from product-service
│   │   └── UserResponse.java            # User data from user-service
│   ├── entity/
│   │   ├── Order.java                    # MongoDB document
│   │   └── OrderItem.java               # Embedded item model
│   ├── kafka/
│   │   └── KafkaProducerService.java     # Publishes OrderEvent to Kafka
│   ├── repo/
│   │   ├── OrderItemRepo.java            # MongoDB repo for OrderItem
│   │   └── OrderRepository.java          # MongoDB repo for Order
│   ├── security/
│   │   └── JwtUtil.java                  # JWT token utility (generate/validate)
│   └── service/
│       ├── OrderService.java             # Service interface
│       └── OrderServiceImpl.java         # Service implementation
└── src/main/resources/
    └── application.properties
```

---

## 3) API Endpoints (Detailed)

Base path: `/order`

---

### 3.1) `POST /order/newOrder` — Create a New Order

**Controller**: `OrderController#createOrder(NewOrder)`

**Request Body** (`NewOrder`):
```json
{
  "userId": 101,
  "items": [
    { "productId": 1, "quantity": 2 },
    { "productId": 2, "quantity": 1 }
  ]
}
```

**Fields**:
| Field        | Type              | Required | Description                    |
|--------------|-------------------|----------|--------------------------------|
| `userId`     | `Long`            | Yes      | ID of the user placing the order |
| `items`      | `List<OrderItem>` | Yes      | List of items to order         |
| `items[].productId`  | `Long`   | Yes      | Product ID (validated against product-service) |
| `items[].quantity`    | `Integer`| Yes      | Quantity to order              |
| `items[].productName`| `String` | No       | Overwritten by product-service response |
| `items[].price`       | `Double`| No       | Overwritten by product-service response |

**Internal Flow**:
1. Calls **user-service** via `UserClient#getUserById(userId)` → `GET /api/auth/user/{id}`
2. If user not found → throws `RuntimeException("User not found")`
3. For **each item**, calls **product-service** via `ProductClient#getProductById(productId)` → `GET /api/products/{id}`
4. If product not found → throws `RuntimeException("Product not found: {id}:{name}")`
5. Enriches each item with `productName` and `price` from product-service response
6. Computes `totalAmount = Σ (price × quantity)`
7. Creates `Order` entity with `status = "PLACED"`, `createdAt = now()`
8. Saves order to MongoDB
9. Publishes `OrderEvent` to Kafka topic `order-events`

**Responses**:
| Status | Body                              |
|--------|-----------------------------------|
| `200`  | `"Order created successfully"`    |
| `500`  | `"Failed to create order{error}"` |

---

### 3.2) `GET /order/{id}` — Get Order by ID

**Controller**: `OrderController#getOrderById(String id)`

**Path Variable**: `id` — MongoDB document ID (String)

**Response** (`OrderResponse`):
```json
{
  "id": "6651abc123def456",
  "userId": 101,
  "items": [
    {
      "productId": 1,
      "productName": "Laptop",
      "quantity": 2,
      "price": 999.99
    }
  ],
  "totalAmount": 1999.98,
  "status": "PLACED",
  "createdAt": "2025-05-01T10:30:00"
}
```

**Error**: Throws `RuntimeException("Order Not found")` if ID doesn't exist.

---

### 3.3) `GET /order/user/{userId}` — Get Latest Order for a User

**Controller**: `OrderController#getUserOrders(Long userId)`

**Path Variable**: `userId` — User's numeric ID

**Behavior**: Returns the **most recent** order for the user (sorted by `createdAt` descending, top 1).

**Response**: Same `OrderResponse` structure as above.

**Error**: Throws `RuntimeException("No Orders found")` if no orders exist for user.

---

### 3.4) `PUT /order/{id}/status?status={status}` — Update Order Status

**Controller**: `OrderController#updateOrderStatus(String id, String status)`

**Parameters**:
| Param    | Type     | In    | Description              |
|----------|----------|-------|--------------------------|
| `id`     | `String` | Path  | Order document ID        |
| `status` | `String` | Query | New status value         |

**Known Status Values**: `PLACED`, `CONFIRMED`, `DELIVERED` (not enum-enforced, any string accepted)

**Response**: `200 OK` — `"Order status updated to {status}"`

---

## 4) Data Model

### 4.1) `Order` — MongoDB Document

Collection: `order` (default from class name)

| Field         | Type               | Constraints       | Description                          |
|---------------|--------------------|--------------------|--------------------------------------|
| `id`          | `String`           | `@Id` (auto-generated) | MongoDB ObjectId                 |
| `userId`      | `Long`             | `@NotNull`         | References user in user-service      |
| `items`       | `List<OrderItem>`  | `@NotEmpty`        | Embedded list of order items         |
| `totalAmount` | `Double`           | `@NotNull`         | Computed sum of (price × quantity)   |
| `status`      | `String`           | `@NotNull`         | PLACED / CONFIRMED / DELIVERED       |
| `createdAt`   | `LocalDateTime`    | —                  | Timestamp of order creation          |

### 4.2) `OrderItem` — Embedded Object

| Field         | Type      | Description                           |
|---------------|-----------|---------------------------------------|
| `productId`   | `Long`    | References product in product-service |
| `productName` | `String`  | Product name (fetched from product-service) |
| `quantity`    | `Integer` | Quantity ordered                      |
| `price`       | `Double`  | Unit price (fetched from product-service) |

---

## 5) Inter-Service Communication

### 5.1) Downstream Feign Calls (THIS SERVICE → OTHER SERVICES)

#### A) User Service (`user-service`)

| Detail          | Value                                |
|-----------------|--------------------------------------|
| **Feign Client**| `UserClient`                         |
| **Service ID**  | `user-service` (Eureka registered)   |
| **Endpoint**    | `GET /api/auth/user/{id}`            |
| **Path Var**    | `id` — `Long` (user ID)             |
| **Returns**     | `UserResponse`                       |

**`UserResponse` DTO** (what this service expects from user-service):
```json
{
  "id": 101,
  "name": "John Doe",
  "email": "john@example.com"
}
```
| Field   | Type     |
|---------|----------|
| `id`    | `Long`   |
| `name`  | `String` |
| `email` | `String` |

> **⚠️ IMPORTANT FOR USER-SERVICE**: You must expose `GET /api/auth/user/{id}` that returns the above JSON structure. The endpoint must be accessible via Eureka service discovery under the name `user-service`.

---

#### B) Product Service (`product-service`)

| Detail          | Value                                  |
|-----------------|----------------------------------------|
| **Feign Client**| `ProductClient`                        |
| **Service ID**  | `product-service` (Eureka registered)  |
| **Endpoint**    | `GET /api/products/{id}`               |
| **Path Var**    | `id` — `Long` (product ID)            |
| **Returns**     | `ProductResponse`                      |

**`ProductResponse` DTO** (what this service expects from product-service):
```json
{
  "id": 1,
  "name": "Laptop",
  "price": 999.99,
  "categoryName": "Electronics"
}
```
| Field          | Type     |
|----------------|----------|
| `id`           | `long`   |
| `name`         | `String` |
| `price`        | `Double` |
| `categoryName` | `String` |

> **⚠️ IMPORTANT FOR PRODUCT-SERVICE**: You must expose `GET /api/products/{id}` that returns the above JSON structure. The endpoint must be accessible via Eureka service discovery under the name `product-service`.

---

### 5.2) Kafka Events Published (THIS SERVICE → KAFKA → CONSUMERS)

| Detail         | Value                                           |
|----------------|-------------------------------------------------|
| **Topic**      | `order-events`                                  |
| **Key**        | `orderId` (String)                              |
| **Value**      | `OrderEvent` (JSON serialized)                  |
| **Serializer** | Key: `StringSerializer`, Value: `JsonSerializer`|
| **Trigger**    | After successful order creation (`placeOrder`)  |

**`OrderEvent` Payload**:
```json
{
  "orderId": "6651abc123def456",
  "userId": 101,
  "items": [
    {
      "productId": 1,
      "productName": "Laptop",
      "quantity": 2,
      "price": 999.99
    }
  ],
  "totalAmount": 1999.98,
  "status": "PLACED",
  "createdAt": "2025-05-01T10:30:00"
}
```

| Field         | Type               | Description                    |
|---------------|--------------------|--------------------------------|
| `orderId`     | `String`           | MongoDB document ID            |
| `userId`      | `Long`             | User who placed the order      |
| `items`       | `List<OrderItem>`  | Items with name, price, qty    |
| `totalAmount` | `Double`           | Total order amount             |
| `status`      | `String`           | Order status (e.g., `PLACED`)  |
| `createdAt`   | `LocalDateTime`    | ISO datetime of order creation |

> **⚠️ FOR CONSUMER SERVICES** (e.g., notification-service, payment-service):
> - Listen on topic `order-events`
> - Deserialize value as JSON with the above structure
> - Use `org.springframework.kafka.support.serializer.JsonDeserializer` or equivalent
> - Configure trusted packages: `com.onlineshopping.order_service.dto`
> - Consumer config example:
>   ```properties
>   spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
>   spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
>   spring.kafka.consumer.properties.spring.json.trusted.packages=*
>   ```

---

## 6) Configuration Reference

### `application.properties` (Full)

```properties
# APPLICATION
spring.application.name=order-service
server.port=8083

# MONGODB
spring.data.mongodb.host=localhost
spring.data.mongodb.port=27017
spring.data.mongodb.database=orderdb
spring.data.mongodb.auto-index-creation=true
spring.data.mongodb.uuid-representation=standard

# EUREKA
eureka.client.service-url.defaultZone=http://localhost:8761/eureka/
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true

# LOGGING
logging.level.org.springframework.data.mongodb.core.MongoTemplate=DEBUG

# KAFKA
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer

# JWT
jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZA==
```

### Configuration Details

| Key | Purpose | Notes |
|-----|---------|-------|
| `server.port=8083` | HTTP listen port | Must not conflict with other services |
| `spring.data.mongodb.*` | MongoDB connection | Database `orderdb` must exist or be auto-created |
| `eureka.client.service-url.defaultZone` | Eureka server URL | Default: `http://localhost:8761/eureka/` |
| `spring.kafka.bootstrap-servers` | Kafka broker(s) | Default: `localhost:9092` |
| `jwt.secret` | Base64-encoded HMAC secret | **Must be identical across all services** that share JWT tokens |

---

## 7) Security

### 7.1) JWT Configuration

- **Library**: JJWT (`io.jsonwebtoken:jjwt-api:0.12.6`)
- **Secret**: Configured via `jwt.secret` property (Base64-encoded)
- **Token Expiration**: 10 minutes (`1000 * 60 * 10` ms)
- **Algorithm**: HMAC (derived from `Keys.hmacShaKeyFor`)

### 7.2) `JwtUtil` Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `generateToken` | `generateToken(String username) → String` | Creates a signed JWT with username as subject, 10-min expiry |
| `validateToken` | `validateToken(String token, String username) → boolean` | Validates token signature, expiry, and subject match |
| `getUsernameFromToken` | (private) `getUsernameFromToken(String token) → String` | Extracts subject claim |
| `getClaims` | (private) `getClaims(String token) → Claims` | Parses and verifies JWT |
| `getSigningKey` | (private) `getSigningKey() → SecretKey` | Decodes Base64 secret to HMAC key |
| `isTokenExpired` | (private) `isTokenExpired(String token) → boolean` | Checks expiration date |

### 7.3) Dependencies Present

- `spring-boot-starter-security`
- `spring-boot-starter-oauth2-resource-server`
- `jjwt-api`, `jjwt-impl`, `jjwt-jackson`

### 7.4) Current Security Status

> **⚠️ NOTE**: As of the current codebase:
> - `JwtUtil` exists but there is **no `SecurityFilterChain`** bean configured.
> - There is **no JWT authentication filter** (`OncePerRequestFilter`) wired into the security chain.
> - There is **no `@PreAuthorize`** or endpoint-level authorization.
> - Spring Security is on the classpath, so by default **all endpoints require Basic Auth** (auto-generated password in logs) unless configured otherwise.

### 7.5) Integration Note for Other Services

- **JWT Secret must match**: If user-service generates JWT tokens, the `jwt.secret` value must be **identical** in all services that validate tokens.
- **Token format**: Standard JWT with `sub` (username), `iat`, `exp` claims.
- Other services should expect this service's endpoints may or may not enforce auth depending on security config completion.

---

## 8) Service Discovery (Eureka)

| Property | Value |
|----------|-------|
| Eureka Server | `http://localhost:8761/eureka/` |
| Registers itself | Yes (`register-with-eureka=true`) |
| Fetches registry | Yes (`fetch-registry=true`) |
| Registered name | `order-service` |

### Services This Service Discovers via Eureka
- `user-service` — for user validation
- `product-service` — for product validation and price lookup

> **⚠️ REQUIREMENT**: An Eureka Server must be running at port `8761` before starting this service.

---

## 9) Observability & Monitoring

### Actuator
- Dependency: `spring-boot-starter-actuator`
- Default endpoints available at `/actuator/*`

### Metrics
- **Prometheus**: `micrometer-registry-prometheus` — exposes `/actuator/prometheus`
- **Tracing**: `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` for distributed tracing

### Resilience
- **Circuit Breaker**: `spring-cloud-starter-circuitbreaker-resilience4j`
- **Resilience4j**: `resilience4j-spring-boot3` (v2.2.0)
- AOP support enabled for annotation-based resilience patterns

---

## 10) Build & Run

### Prerequisites
- Java 21+
- Maven 3.9+
- MongoDB running on `localhost:27017`
- Kafka running on `localhost:9092`
- Eureka Server running on `localhost:8761`
- `user-service` and `product-service` registered in Eureka

### Build
```bash
./mvnw clean package
```

### Run
```bash
./mvnw spring-boot:run
```
Or:
```bash
java -jar target/order-service-1.0.0-SNAPSHOT.jar
```

### Docker Image
```bash
./mvnw spring-boot:build-image
# Image: onlineshopping/order-service:1.0.0-SNAPSHOT
```

### Maven Profiles
| Profile | Purpose | Activation |
|---------|---------|------------|
| `dev` | Local development, skips slow checks | Default |
| `ci` | CI pipeline, runs everything | `-Pci` |
| `security-scan` | OWASP dependency vulnerability scan | `-Psecurity-scan` |

---

## 11) API Documentation

- **SpringDoc OpenAPI**: Available at `http://localhost:8083/swagger-ui.html`
- **OpenAPI JSON**: `http://localhost:8083/v3/api-docs`

---

## 12) Known Issues & Gaps

| # | Issue | Impact |
|---|-------|--------|
| 1 | `KafkaProducerService.kafkaTemplate` has no `@Autowired` or constructor injection | Potential `NullPointerException` at runtime |
| 2 | `OrderServiceImpl` constructor accepts two `KafkaProducerService` params | Code smell / wiring ambiguity |
| 3 | `spring-boot-starter-security` duplicated in `pom.xml` | Dependency clutter |
| 4 | No `SecurityFilterChain` / JWT filter configured | Endpoints may default to Basic Auth |
| 5 | No `@ControllerAdvice` global exception handler | Generic 500 errors returned |
| 6 | No `@Valid` on request body in controller | Validation annotations on entity not enforced |
| 7 | Order status is a plain `String`, not an enum | Any string accepted, no validation |

---

## 13) Integration Checklist for Other Services

Use this checklist when configuring another microservice to work with `order-service`:

### If your service CALLS order-service:
- [ ] Register your service in Eureka
- [ ] Create a Feign client with `@FeignClient(name = "order-service")`
- [ ] Endpoints available:
  - `POST /order/newOrder`
  - `GET /order/{id}`
  - `GET /order/user/{userId}`
  - `PUT /order/{id}/status?status=...`

### If your service IS CALLED BY order-service (user-service):
- [ ] Register as `user-service` in Eureka
- [ ] Expose `GET /api/auth/user/{id}`
- [ ] Return JSON: `{ "id": Long, "name": String, "email": String }`

### If your service IS CALLED BY order-service (product-service):
- [ ] Register as `product-service` in Eureka
- [ ] Expose `GET /api/products/{id}`
- [ ] Return JSON: `{ "id": long, "name": String, "price": Double, "categoryName": String }`

### If your service CONSUMES Kafka events from order-service:
- [ ] Listen on topic: `order-events`
- [ ] Key type: `String` (orderId)
- [ ] Value type: JSON — `OrderEvent` structure (see Section 5.2)
- [ ] Configure `JsonDeserializer` with trusted packages
- [ ] Set consumer group ID

### Shared Configuration:
- [ ] Same Eureka server URL across all services
- [ ] Same Kafka bootstrap servers
- [ ] Same `jwt.secret` value (Base64-encoded) for JWT validation
- [ ] MongoDB is isolated — each service has its own database

---

## 14) Complete DTO Reference (for Cross-Service Compatibility)

### `NewOrder` (Input)
```java
public class NewOrder {
    private Long userId;
    private List<OrderItem> items;
}
```

### `OrderItem` (Embedded)
```java
public class OrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
```

### `OrderResponse` (Output)
```java
public class OrderResponse {
    private String id;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

### `OrderEvent` (Kafka Payload)
```java
public class OrderEvent {
    private String orderId;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

### `UserResponse` (Expected from user-service)
```java
public class UserResponse {
    private Long id;
    private String name;
    private String email;
}
```

### `ProductResponse` (Expected from product-service)
```java
public class ProductResponse {
    private long id;
    private String name;
    private Double price;
    private String categoryName;
}
```

---

> **This README is designed to be shared with other microservice projects so they can understand, configure, and integrate with the order-service without needing access to its source code.**

---

## ⚠️ MISSING IMPLEMENTATIONS — Must Add

### 🔴 CRITICAL (Service will not work without these)

#### A. SecurityConfig.java — MISSING

**Issue:** No `SecurityFilterChain` configured. Spring Security defaults to Basic Auth blocking all requests.

**Create:** `src/main/java/com/onlineshopping/order_service/security/SecurityConfig.java`
```java
package com.onlineshopping.order_service.security;

import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private GatewayAuthFilter gatewayAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

#### B. GatewayAuthFilter.java — MISSING

**Issue:** Service cannot read `X-User-Id`, `X-Username`, `X-User-Role` headers from Gateway.

**Create:** `src/main/java/com/onlineshopping/order_service/security/GatewayAuthFilter.java`
```java
package com.onlineshopping.order_service.security;

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
                String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                authorities.add(new SimpleGrantedAuthority(roleWithPrefix));
            }

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
            auth.setDetails(userId);   // Store userId for use in service layer
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
```

#### C. Fix KafkaProducerService — Missing @Autowired / Constructor Injection

**Issue (Known Issue #1):** `kafkaTemplate` has no `@Autowired` or constructor injection → `NullPointerException` at runtime.

**Fix `KafkaProducerService.java`:**
```java
@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    // Use constructor injection — not @Autowired field injection
    public KafkaProducerService(KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendOrderEvent(OrderEvent event) {
        kafkaTemplate.send("order-events", event.getOrderId(), event);
    }
}
```

#### D. Fix OrderServiceImpl Constructor — Duplicate KafkaProducerService Parameter

**Issue (Known Issue #2):** Constructor accepts two `KafkaProducerService` params → wiring ambiguity.

**Fix:** Remove duplicate parameter, keep only one.

#### E. Fix getUserOrders Return Type — Should Return List

**Issue:** `getUserOrders()` returns single `OrderResponse` but user can have many orders.

**Fix `OrderController.java`:**
```java
import java.util.List;

@GetMapping("/user/{userId}")
public ResponseEntity<List<OrderResponse>> getUserOrders(@PathVariable Long userId) {
    return ResponseEntity.ok(orderService.getUserOrders(userId));
}
```

**Fix `OrderService.java` interface:**
```java
List<OrderResponse> getUserOrders(Long userId);
```

**Fix `OrderServiceImpl.java`:**
```java
@Override
public List<OrderResponse> getUserOrders(Long userId) {
    return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
}
```

**Add to `OrderRepository.java`:**
```java
List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);
```

#### F. Fix JWT Secret — Must Match Gateway
```properties
# application.properties
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

---

### 🟡 IMPORTANT

#### G. FeignConfig — Header Propagation for Feign Calls

**Issue:** When Order Service calls User/Product Service via Feign, Gateway-forwarded headers (`X-User-Id` etc.) are not automatically passed on. Product/User services won't have SecurityContext set.

**Create:** `src/main/java/com/onlineshopping/order_service/config/FeignConfig.java`
```java
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String userId   = request.getHeader("X-User-Id");
                String username = request.getHeader("X-Username");
                String role     = request.getHeader("X-User-Role");
                if (userId != null)   requestTemplate.header("X-User-Id", userId);
                if (username != null) requestTemplate.header("X-Username", username);
                if (role != null)     requestTemplate.header("X-User-Role", role);
            }
        };
    }
}
```

#### H. Add Order Status Enum (Currently Plain String)

**Issue:** Any string is accepted for order status — no validation.

```java
public enum OrderStatus {
    PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}
```

Update `Order.java` field:
```java
private OrderStatus status;   // was: private String status
```

Update `updateOrderStatus` to validate:
```java
OrderStatus.valueOf(status.toUpperCase());  // throws if invalid
```

#### I. Add @Valid on Request Body in Controller
```java
// OrderController.java
@PostMapping("/newOrder")
public ResponseEntity<String> createOrder(@RequestBody @Valid NewOrder newOrder) { ... }
```

Add validation annotations to `NewOrder.java`:
```java
public class NewOrder {
    @NotNull(message = "userId is required")
    private Long userId;

    @NotEmpty(message = "Order must have at least one item")
    private List<OrderItemRequest> items;
}
```

---

### ✅ Already Correct

| Item | Status |
|------|--------|
| Feign clients (`UserClient`, `ProductClient`) | ✅ |
| Kafka producer (`order-events` topic) | ✅ (fix injection first) |
| MongoDB Order/OrderItem entities | ✅ |
| Eureka registration | ✅ |
| `createOrder` flow (user + product validation) | ✅ |

---

## 🧪 Postman Testing Guide

> **All requests go through API Gateway at `http://localhost:8080`**
> All order endpoints require a valid JWT token (Bearer).

### Prerequisites — Start services in this order:
1. Redis (`localhost:6379`)
2. MySQL (`localhost:3306`) — for User Service
3. MongoDB (`localhost:27017`) — for Order Service
4. Kafka (`localhost:9092`) — for order events
5. Eureka Server (`localhost:8761`)
6. User Service (`localhost:8081`)
7. Product Service (`localhost:8082`)
8. Order Service (`localhost:8083`)
9. API Gateway (`localhost:8080`)

> Get JWT token first: `POST http://localhost:8080/api/auth/login`

---

### 1. Place a New Order
```
Method:  POST
URL:     http://localhost:8080/api/orders/newOrder
Headers:
  Authorization: Bearer <your_jwt_token>
  Content-Type: application/json

Body (raw JSON):
{
    "userId": 1,
    "items": [
        { "productId": 1, "quantity": 2 },
        { "productId": 2, "quantity": 1 }
    ]
}

Expected: 200 OK — "Order created successfully"

Internal flow:
  1. Calls GET http://user-service/api/auth/user/1      → validates user exists
  2. Calls GET http://product-service/api/products/1    → gets name + price
  3. Calls GET http://product-service/api/products/2    → gets name + price
  4. Saves order to MongoDB with status = "PLACED"
  5. Publishes OrderEvent to Kafka topic "order-events"

Error: 500 if userId not found in User Service
Error: 500 if productId not found in Product Service
Rate Limit: 20 requests/min (Gateway — payment protection)
```

### 2. Get Order by ID
```
Method:  GET
URL:     http://localhost:8080/api/orders/6651abc123def456
Headers: Authorization: Bearer <your_jwt_token>

Note: Replace "6651abc123def456" with actual MongoDB ObjectId from order creation

Expected Response:
{
    "id": "6651abc123def456",
    "userId": 1,
    "items": [
        { "productId": 1, "productName": "Laptop", "quantity": 2, "price": 999.99 },
        { "productId": 2, "productName": "Mouse",  "quantity": 1, "price": 29.99  }
    ],
    "totalAmount": 2029.97,
    "status": "PLACED",
    "createdAt": "2026-05-07T14:30:00"
}

Error: 500 — "Order Not found" if ID doesn't exist
```

### 3. Get All Orders for a User
```
Method:  GET
URL:     http://localhost:8080/api/orders/user/1
Headers: Authorization: Bearer <your_jwt_token>

Note: Returns List<OrderResponse> after getUserOrders fix is applied
      Currently returns single OrderResponse (bug — fix item E above)

Expected Response (after fix):
[
    { "id": "...", "userId": 1, "status": "DELIVERED", ... },
    { "id": "...", "userId": 1, "status": "PLACED",    ... }
]

Error: 500 — "No Orders found" if user has no orders
```

### 4. Update Order Status
```
Method:  PUT
URL:     http://localhost:8080/api/orders/6651abc123def456/status?status=CONFIRMED
Headers: Authorization: Bearer <your_jwt_token>

Valid status values: PLACED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED

Expected: 200 OK — "Order status updated to CONFIRMED"
Effect:   @CacheEvict removes order from Redis cache (after caching is added)
```

---

### 🔁 Full End-to-End Test Flow
```
Step 1: Register user
  POST http://localhost:8080/api/auth/register
  { "username": "testuser", "email": "test@test.com", "password": "pass123", "role": "USER" }

Step 2: Register admin
  POST http://localhost:8080/api/auth/register
  { "username": "admin", "email": "admin@test.com", "password": "admin123", "role": "ADMIN" }

Step 3: Login as admin → get ADMIN token
  POST http://localhost:8080/api/auth/login
  { "username": "admin", "password": "admin123" }

Step 4: Create a product (ADMIN token)
  POST http://localhost:8080/api/products
  Authorization: Bearer <ADMIN_token>
  { "name": "Laptop", "description": "Fast laptop", "price": 999.99, "stock": 10, "categoryId": 1 }

Step 5: Login as user → get USER token
  POST http://localhost:8080/api/auth/login
  { "username": "testuser", "password": "pass123" }

Step 6: Place order (USER token)
  POST http://localhost:8080/api/orders/newOrder
  Authorization: Bearer <USER_token>
  { "userId": 1, "items": [{ "productId": 1, "quantity": 2 }] }

Step 7: Check order
  GET http://localhost:8080/api/orders/<orderId_from_step6>
  Authorization: Bearer <USER_token>

Step 8: Update status (any authenticated user currently — add ADMIN check if needed)
  PUT http://localhost:8080/api/orders/<orderId>/status?status=CONFIRMED
  Authorization: Bearer <ADMIN_token>
```

---

### ❌ Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` | No/invalid JWT | Add Authorization Bearer header |
| `500 User not found` | userId in request doesn't exist | Register user first, use correct ID |
| `500 Product not found` | productId doesn't exist | Create product first |
| `500 NullPointerException` on Kafka | `kafkaTemplate` not injected | Fix KafkaProducerService (Item C above) |
| `401 Basic Auth prompt` | No SecurityFilterChain | Add SecurityConfig + GatewayAuthFilter (Items A & B) |
| `500 Failed to create order` | Order Service can't call User/Product Service | Ensure both services are running and in Eureka |
| `429 Too Many Requests` | Rate limit hit (20/min for orders) | Wait 60s |


