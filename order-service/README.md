# Order Service (`order-service`)

Order management microservice for the **Online Shopping** system.  
Validates user/product references via downstream services, stores orders in MongoDB, publishes order events to Kafka, and caches responses with Redis.

> **Last Updated:** April 29, 2026

---

## 1) Service Overview

| Property            | Value                                      |
|---------------------|--------------------------------------------|
| **Service Name**    | `order-service`                            |
| **Port**            | `8083`                                     |
| **Database**        | MongoDB (`orderdb`)                        |
| **Cache**           | Redis (TTL: 5 min)                         |
| **Discovery**       | Eureka                                     |
| **Messaging**       | Kafka (producer only)                      |
| **Java Version**    | 21                                         |
| **Spring Boot**     | 3.2.5                                      |
| **Spring Cloud**    | 2023.0.1                                   |
| **Base Package**    | `com.onlineshopping.order_service`         |

### Main Responsibilities
- Create orders (with user & product validation from other services)
- Fetch order by ID (cached in Redis)
- Fetch latest order of a user
- Update order status (evicts cache)
- Publish `OrderEvent` to Kafka topic `order-events`

---

## 2) Project Structure

```
order-service/
├── src/main/java/com/onlineshopping/order_service/
│   ├── OrderServiceApplication.java          # Boot entry point (@EnableFeignClients, @EnableDiscoveryClient)
│   ├── client/
│   │   ├── ProductClient.java                # Feign client → product-service
│   │   └── UserClient.java                   # Feign client → user-service
│   ├── config/
│   │   ├── FeignConfig.java                  # Propagates X-User-* headers to downstream Feign calls
│   │   └── RedisConfig.java                  # Redis cache manager (5-min TTL, @EnableCaching)
│   ├── controller/
│   │   └── OrderController.java              # REST endpoints under /order
│   ├── dto/
│   │   ├── NewOrder.java                     # Create-order request DTO
│   │   ├── OrderEvent.java                   # Kafka event payload
│   │   ├── OrderItemRequest.java             # Item request DTO (productId, quantity)
│   │   ├── OrderResponse.java                # Order response DTO
│   │   ├── ProductResponse.java              # Product data from product-service
│   │   └── UserResponse.java                 # User data from user-service
│   ├── entity/
│   │   ├── Order.java                        # MongoDB document
│   │   └── OrderItem.java                    # Embedded item model
│   ├── exception/
│   │   └── GlobalExceptionHandler.java       # @RestControllerAdvice for RuntimeException & FeignException
│   ├── kafka/
│   │   └── KafkaProducerService.java         # Publishes OrderEvent to Kafka topic "order-events"
│   ├── repo/
│   │   ├── OrderItemRepo.java                # MongoDB repo for OrderItem
│   │   └── OrderRepository.java              # MongoDB repo for Order
│   ├── security/
│   │   ├── GatewayAuthFilter.java            # OncePerRequestFilter — reads X-User-Id/Username/Role headers
│   │   ├── JwtUtil.java                      # JWT utility (legacy — not used in security chain)
│   │   └── SecurityConfig.java               # SecurityFilterChain — stateless, gateway-header auth
│   └── service/
│       ├── OrderService.java                 # Service interface
│       └── OrderServiceImpl.java             # Implementation with caching + circuit breaker
└── src/main/resources/
    └── application.properties
```

---

## 3) Security Architecture

### 3.1) Authentication Model — Gateway Header Trust

This service does **NOT** validate JWT tokens itself. It trusts the **API Gateway** to authenticate users and forward identity via HTTP headers.

**Headers expected from API Gateway:**

| Header         | Type     | Required | Description                          |
|----------------|----------|----------|--------------------------------------|
| `X-User-Id`    | `String` | Yes      | Authenticated user's ID              |
| `X-Username`   | `String` | Yes      | Authenticated user's username        |
| `X-User-Role`  | `String` | No       | User's role (e.g., `ADMIN`, `USER`)  |

### 3.2) `GatewayAuthFilter` — How It Works

**Class:** `com.onlineshopping.order_service.security.GatewayAuthFilter`  
**Type:** `OncePerRequestFilter` (runs once per request)

**Logic:**
1. Extracts `X-User-Id`, `X-Username`, `X-User-Role` from incoming request headers
2. If `X-Username` is present and non-empty:
   - Creates `UsernamePasswordAuthenticationToken` with username as principal
   - Adds `ROLE_{role}` as granted authority (e.g., `ROLE_ADMIN`)
   - Stores `userId`, `username`, `role` in authentication details (`Map`)
   - Sets the authentication in `SecurityContextHolder`
3. If header is missing → request proceeds unauthenticated (will be rejected by security config)

**Accessing user info in controllers/services:**
```java
SecurityContextHolder.getContext().getAuthentication().getPrincipal();  // username
Map<String, String> details = (Map<String, String>) auth.getDetails();
String userId = details.get("userId");
String role = details.get("role");
```

### 3.3) `SecurityConfig` — Filter Chain

**Class:** `com.onlineshopping.order_service.security.SecurityConfig`

| Rule | Description |
|------|-------------|
| CSRF | Disabled (stateless API) |
| Session | `STATELESS` — no HTTP session |
| `/actuator/**` | Permitted (no auth required) |
| `/swagger-ui/**`, `/v3/api-docs/**` | Permitted (no auth required) |
| All other requests | **Authenticated** (requires valid gateway headers) |
| Filter order | `GatewayAuthFilter` runs **before** `UsernamePasswordAuthenticationFilter` |

### 3.4) `JwtUtil` (Legacy)

**Class:** `com.onlineshopping.order_service.security.JwtUtil`

This class exists but is **NOT wired into the security chain**. JWT validation is handled by the API Gateway. This class is retained for potential direct JWT validation if needed.

- **Library:** JJWT (`io.jsonwebtoken:jjwt-api:0.12.6`)
- **Secret:** `jwt.secret` property (Base64-encoded HMAC key)
- **Token Expiration:** 10 minutes
- **Methods:** `generateToken(username)`, `validateToken(token, username)`, `getClaims(token)`

### 3.5) JWT Secret

```properties
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

> **⚠️ CRITICAL:** This value **MUST be identical** across all services (API Gateway, user-service, order-service, etc.) for JWT token consistency.

---

## 4) API Endpoints

Base path: `/order`

All endpoints require authentication via gateway headers (`X-User-Id`, `X-Username`, `X-User-Role`).

---

### 4.1) `POST /order/newOrder` — Create a New Order

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

| Field                | Type       | Required | Description |
|----------------------|------------|----------|-------------|
| `userId`             | `Long`     | Yes      | User placing the order (validated against user-service) |
| `items`              | `List`     | Yes      | List of items |
| `items[].productId`  | `Long`     | Yes      | Product ID (validated against product-service) |
| `items[].quantity`   | `Integer`  | Yes      | Quantity to order |

**Internal Flow:**
1. Calls `UserClient.getUserById(userId)` → `GET user-service/api/auth/user/{id}` (with circuit breaker `userService`)
2. If user not found → `RuntimeException("User not found")`
3. For each item, calls `ProductClient.getProductById(productId)` → `GET product-service/api/products/{id}` (with circuit breaker `productService`)
4. If product not found → `RuntimeException("Product not found: {id}:{name}")`
5. Enriches each item with `productName` and `price` from product-service
6. Computes `totalAmount = Σ(price × quantity)`
7. Creates `Order` document with `status = "PLACED"`, `createdAt = now()`
8. Saves to MongoDB
9. Publishes `OrderEvent` to Kafka topic `order-events`

**Responses:**

| Status | Body |
|--------|------|
| `200`  | `"Order created successfully"` |
| `500`  | `"Failed to create order{error message}"` |

---

### 4.2) `GET /order/{id}` — Get Order by ID

**Path Variable:** `id` — MongoDB document ID (String)

**Caching:** `@Cacheable(value = "orders", key = "#id")` — cached in Redis for 5 minutes.

**Response** (`OrderResponse`):
```json
{
  "id": "6651abc123def456",
  "userId": 101,
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "price": 999.99 }
  ],
  "totalAmount": 1999.98,
  "status": "PLACED",
  "createdAt": "2025-05-01T10:30:00"
}
```

**Error:** `RuntimeException("Order Not found")` → 500 via GlobalExceptionHandler.

---

### 4.3) `GET /order/user/{userId}` — Get Latest Order for a User

**Path Variable:** `userId` — User's numeric ID (Long)

**Behavior:** Returns the **most recent** order (sorted by `createdAt` desc, top 1).

**Response:** Same `OrderResponse` structure as above.

**Error:** `RuntimeException("No Orders found")` if no orders exist.

---

### 4.4) `PUT /order/{id}/status?status={status}` — Update Order Status

| Param    | Type     | In    | Description |
|----------|----------|-------|-------------|
| `id`     | `String` | Path  | Order document ID |
| `status` | `String` | Query | New status value |

**Caching:** `@CacheEvict(value = "orders", key = "#id")` — evicts cached entry on update.

**Known Status Values:** `PLACED`, `CONFIRMED`, `DELIVERED` (not enum-enforced, any string accepted).

**Response:** `200 OK` — `"Order status updated to {status}"`

---

## 5) Data Model

### 5.1) `Order` — MongoDB Document

Collection: `order`

| Field         | Type               | Annotations             | Description |
|---------------|--------------------|--------------------------|-------------|
| `id`          | `String`           | `@Id` (auto-generated)  | MongoDB ObjectId |
| `userId`      | `Long`             | `@NotNull`               | References user in user-service |
| `items`       | `List<OrderItem>`  | `@NotEmpty`              | Embedded list of items |
| `totalAmount` | `Double`           | `@NotNull`               | Computed sum of (price × quantity) |
| `status`      | `String`           | `@NotNull`               | PLACED / CONFIRMED / DELIVERED |
| `createdAt`   | `LocalDateTime`    | —                        | Timestamp of order creation |

### 5.2) `OrderItem` — Embedded Object

| Field         | Type      | Description |
|---------------|-----------|-------------|
| `productId`   | `Long`    | References product in product-service |
| `productName` | `String`  | Product name (fetched from product-service) |
| `quantity`    | `Integer` | Quantity ordered |
| `price`       | `Double`  | Unit price (fetched from product-service) |

---

## 6) Inter-Service Communication

### 6.1) Feign Clients (Order Service → Other Services)

#### Header Propagation

`FeignConfig` automatically forwards `X-User-Id`, `X-Username`, `X-User-Role` headers to all downstream Feign calls. This ensures downstream services receive the authenticated user's identity.

---

#### A) User Service (`user-service`)

| Detail          | Value |
|-----------------|-------|
| **Feign Client**| `UserClient` |
| **Service ID**  | `user-service` (Eureka) |
| **Endpoint**    | `GET /api/auth/user/{id}` |
| **Path Var**    | `id` — `Long` (user ID) |
| **Returns**     | `UserResponse` |
| **Circuit Breaker** | `userService` (fallback throws `ServiceUnavailableException`) |

**Expected `UserResponse` from user-service:**
```json
{ "id": 101, "username": "john_doe", "email": "john@example.com" }
```

| Field      | Type     | Notes |
|------------|----------|-------|
| `id`       | `Long`   | |
| `username` | `String` | Field name is `username` (not `name`) |
| `email`    | `String` | |

> **⚠️ FOR USER-SERVICE:** Expose `GET /api/auth/user/{id}` returning the above JSON. Register as `user-service` in Eureka.

---

#### B) Product Service (`product-service`)

| Detail          | Value |
|-----------------|-------|
| **Feign Client**| `ProductClient` |
| **Service ID**  | `product-service` (Eureka) |
| **Endpoint**    | `GET /api/products/{id}` |
| **Path Var**    | `id` — `Long` (product ID) |
| **Returns**     | `ProductResponse` |
| **Circuit Breaker** | `productService` (fallback throws `ServiceUnavailableException`) |

**Expected `ProductResponse` from product-service:**
```json
{ "id": 1, "name": "Laptop", "price": 999.99, "categoryName": "Electronics" }
```

| Field          | Type     |
|----------------|----------|
| `id`           | `long`   |
| `name`         | `String` |
| `price`        | `Double` |
| `categoryName` | `String` |

> **⚠️ FOR PRODUCT-SERVICE:** Expose `GET /api/products/{id}` returning the above JSON. Register as `product-service` in Eureka.

---

### 6.2) Kafka Events Published (Order Service → Kafka → Consumers)

| Detail         | Value |
|----------------|-------|
| **Topic**      | `order-events` |
| **Key**        | `orderId` (String) |
| **Value**      | `OrderEvent` (JSON serialized) |
| **Serializer** | Key: `StringSerializer`, Value: `JsonSerializer` |
| **Trigger**    | After successful order creation in `placeOrder()` |

**`OrderEvent` Payload:**
```json
{
  "orderId": "6651abc123def456",
  "userId": 101,
  "items": [
    { "productId": 1, "productName": "Laptop", "quantity": 2, "price": 999.99 }
  ],
  "totalAmount": 1999.98,
  "status": "PLACED",
  "createdAt": "2025-05-01T10:30:00"
}
```

| Field         | Type               | Description |
|---------------|--------------------|-------------|
| `orderId`     | `String`           | MongoDB document ID |
| `userId`      | `Long`             | User who placed the order |
| `items`       | `List<OrderItem>`  | Items with productId, productName, quantity, price |
| `totalAmount` | `Double`           | Total order amount |
| `status`      | `String`           | Order status (e.g., `PLACED`) |
| `createdAt`   | `LocalDateTime`    | ISO datetime of order creation |

> **⚠️ FOR CONSUMER SERVICES** (e.g., notification-service, payment-service):
> - Listen on topic: `order-events`
> - Deserialize value as JSON with the above structure
> - Use `JsonDeserializer` with trusted packages: `com.onlineshopping.order_service.dto`
> - Example consumer config:
>   ```properties
>   spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
>   spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
>   spring.kafka.consumer.properties.spring.json.trusted.packages=*
>   ```

---

## 7) Configuration Reference

### `application.properties` (Complete)

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

# KAFKA PRODUCER
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer

# JWT
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==

# REDIS CACHE
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms

# CIRCUIT BREAKER - User Service
resilience4j.circuitbreaker.instances.userService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.userService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.userService.waitDurationInOpenState=10000
resilience4j.circuitbreaker.instances.userService.permittedNumberOfCallsInHalfOpenState=3

# CIRCUIT BREAKER - Product Service
resilience4j.circuitbreaker.instances.productService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.productService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.productService.waitDurationInOpenState=10000
```

### Configuration Summary

| Key | Purpose | Notes |
|-----|---------|-------|
| `server.port=8083` | HTTP port | Must not conflict with other services |
| `spring.data.mongodb.*` | MongoDB connection | DB `orderdb` auto-created |
| `eureka.client.service-url.defaultZone` | Eureka server | `http://localhost:8761/eureka/` |
| `spring.kafka.bootstrap-servers` | Kafka broker | `localhost:9092` |
| `jwt.secret` | Base64 HMAC secret | **Must match all services** |
| `spring.data.redis.*` | Redis connection | `localhost:6379` |
| `resilience4j.circuitbreaker.*` | Circuit breaker config | 50% failure threshold, 10s open wait |

---

## 8) Caching (Redis)

### Configuration

**Class:** `com.onlineshopping.order_service.config.RedisConfig`

- `@EnableCaching` enabled
- `RedisCacheManager` with 5-minute TTL
- Null values are NOT cached

### Cached Methods

| Method | Annotation | Cache Name | Key | Behavior |
|--------|-----------|------------|-----|----------|
| `getOrderById(id)` | `@Cacheable` | `orders` | `#id` | Caches order response for 5 min |
| `updateOrderStatus(id, status)` | `@CacheEvict` | `orders` | `#id` | Evicts cache when status changes |

---

## 9) Circuit Breaker (Resilience4j)

### `userService` Circuit Breaker

| Property | Value |
|----------|-------|
| Sliding window size | 10 calls |
| Failure rate threshold | 50% |
| Wait duration in open state | 10 seconds |
| Permitted calls in half-open | 3 |
| Fallback | Throws `ServiceUnavailableException("User service temporarily unavailable")` |

### `productService` Circuit Breaker

| Property | Value |
|----------|-------|
| Sliding window size | 10 calls |
| Failure rate threshold | 50% |
| Wait duration in open state | 10 seconds |
| Fallback | Throws `ServiceUnavailableException("Product service temporarily unavailable")` |

---

## 10) Exception Handling

**Class:** `com.onlineshopping.order_service.exception.GlobalExceptionHandler`

| Exception | HTTP Status | Response Body |
|-----------|-------------|---------------|
| `RuntimeException` | `500` | `{ timestamp, status: 500, error: "Internal Server Error", message }` |
| `FeignException` | Original status code | `{ timestamp, status, error: "Service Communication Error", message }` |

---

## 11) Service Discovery (Eureka)

| Property | Value |
|----------|-------|
| Eureka Server | `http://localhost:8761/eureka/` |
| Registers itself | Yes |
| Fetches registry | Yes |
| Registered name | `order-service` |

### Services Discovered via Eureka
- `user-service` — user validation
- `product-service` — product validation and price lookup

> **⚠️ REQUIREMENT:** Eureka Server must be running at port `8761` before starting this service.

---

## 12) Observability & Monitoring

| Feature | Dependency | Endpoint |
|---------|-----------|----------|
| Actuator | `spring-boot-starter-actuator` | `/actuator/*` |
| Prometheus | `micrometer-registry-prometheus` | `/actuator/prometheus` |
| Distributed Tracing | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | — |
| Circuit Breaker | `spring-cloud-starter-circuitbreaker-resilience4j` | — |

---

## 13) Build & Run

### Prerequisites
- Java 21+
- Maven 3.9+
- MongoDB on `localhost:27017`
- Redis on `localhost:6379`
- Kafka on `localhost:9092`
- Eureka Server on `localhost:8761`
- `user-service` and `product-service` registered in Eureka

### Build
```bash
./mvnw clean package
```

### Run
```bash
./mvnw spring-boot:run
```

### Docker Image
```bash
./mvnw spring-boot:build-image
```

---

## 14) Complete DTO Reference (for Cross-Service Compatibility)

### `NewOrder` (Input — POST /order/newOrder)
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NewOrder {
    private Long userId;
    private List<OrderItem> items;
}
```

### `OrderItem` (Embedded in Order & used in DTOs)
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class OrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
```

### `OrderResponse` (Output — GET endpoints)
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {
    private String id;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

### `OrderEvent` (Kafka Payload — topic: order-events)
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class OrderEvent {
    private String orderId;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

### `UserResponse` (Expected FROM user-service)
```java
@Data @NoArgsConstructor @AllArgsConstructor
public class UserResponse {
    private Long id;
    private String username;  // NOTE: field is "username", not "name"
    private String email;
}
```

### `ProductResponse` (Expected FROM product-service)
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ProductResponse {
    private long id;
    private String name;
    private Double price;
    private String categoryName;
}
```

---

## 15) Integration Checklist for Other Services

### If your service CALLS order-service:
- [ ] Register your service in Eureka
- [ ] Create Feign client: `@FeignClient(name = "order-service")`
- [ ] Forward `X-User-Id`, `X-Username`, `X-User-Role` headers in Feign calls
- [ ] Available endpoints:
  - `POST /order/newOrder` — body: `{ userId, items: [{ productId, quantity }] }`
  - `GET /order/{id}` — returns `OrderResponse`
  - `GET /order/user/{userId}` — returns latest `OrderResponse`
  - `PUT /order/{id}/status?status=...` — updates status

### If your service IS CALLED BY order-service (user-service):
- [ ] Register as `user-service` in Eureka
- [ ] Expose `GET /api/auth/user/{id}`
- [ ] Return JSON: `{ "id": Long, "username": String, "email": String }`
- [ ] Accept and trust `X-User-Id`, `X-Username`, `X-User-Role` headers

### If your service IS CALLED BY order-service (product-service):
- [ ] Register as `product-service` in Eureka
- [ ] Expose `GET /api/products/{id}`
- [ ] Return JSON: `{ "id": long, "name": String, "price": Double, "categoryName": String }`
- [ ] Accept and trust `X-User-Id`, `X-Username`, `X-User-Role` headers

### If your service CONSUMES Kafka events from order-service:
- [ ] Listen on topic: `order-events`
- [ ] Key type: `String` (orderId)
- [ ] Value type: JSON — `OrderEvent` structure (see Section 6.2)
- [ ] Configure `JsonDeserializer` with trusted packages
- [ ] Set unique consumer group ID

### Shared Configuration (MUST match across all services):
- [ ] Same Eureka server URL: `http://localhost:8761/eureka/`
- [ ] Same Kafka bootstrap servers: `localhost:9092`
- [ ] Same `jwt.secret`: `dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==`
- [ ] MongoDB is isolated — each service uses its own database

---

## 16) Known Issues

| # | Issue | Impact |
|---|-------|--------|
| 1 | `KafkaProducerService.kafkaTemplate` has no `@Autowired` or constructor injection | Potential `NullPointerException` at runtime |
| 2 | `OrderServiceImpl` constructor accepts two `KafkaProducerService` params | Code smell / wiring ambiguity |
| 3 | No `@Valid` on `@RequestBody` in controller | Validation annotations on entity not enforced |
| 4 | Order status is plain `String`, not enum | Any string accepted, no validation |
| 5 | `JwtUtil.java` exists but is not used in security chain | Dead code |

---

> **This README is designed to be shared with other microservice projects so they can understand, configure, and integrate with order-service without needing access to its source code.**

