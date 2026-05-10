# Order Service (`order-service`)

Order management microservice for the **Online Shopping** system.  
It validates user/product references via downstream services, stores orders in MongoDB, and publishes order events to Kafka.

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

