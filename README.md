# 🛒 Online Shopping — Microservices E-Commerce Backend

> Consolidated project documentation — merged from all service READMEs and the architecture design doc.
> **Last Updated:** July 14, 2026 · **Spring Boot:** 3.2.5 · **Spring Cloud:** 2023.0.1 · **Java:** 21

A microservices-based e-commerce backend built with **Spring Boot**, **Spring Cloud Gateway**, **Eureka**,
**Redis**, **Apache Kafka**, **MySQL**, and **MongoDB**, using a **Choreography-Based Saga Pattern** for
distributed order/inventory consistency, plus an optional **Spring AI** service for chat, semantic search,
and recommendations.

---

## 📐 Architecture Overview

```
                                   CLIENT LAYER
                     (Web Browser / Mobile App / Postman / curl)
                                        │ HTTP
                                        ▼
                    ┌─────────────────────────────────────────┐
                    │   API GATEWAY (Spring Cloud Gateway)     │
                    │              Port: 8080                 │
                    │  Rate Limiting → JWT Auth → Redis Cache  │
                    └──────────────────┬────────────────────────┘
                                        │ X-User-Id / X-Username / X-User-Role
          ┌───────────────┬────────────┼────────────┬───────────────┬───────────────┐
          ▼               ▼            ▼            ▼               ▼               ▼
   USER-SERVICE   PRODUCT-SERVICE  ORDER-SERVICE  INVENTORY-SVC  NOTIFICATION-SVC  AI-SERVICE
      :8081            :8082          :8083          :8084           :8085          :8086
    (MySQL)          (MySQL)        (MongoDB)       (MySQL)      (MySQL, no route)  (PGVector)

                     All services register with EUREKA SERVER (:8761)
```

### Asynchronous / Saga Flow (Kafka)

```
Order Service (:8083)
   │ publish OrderEvent { orderId, userId, userEmail, items, totalAmount, status=PLACED }
   ▼
Kafka: order-events (3 partitions)
   │ consumed by  [group: inventory-service-group]
   ▼
Inventory Service (:8084)
   ├─ Idempotency check (ProcessedEvent table) — skip duplicate deliveries
   ├─ Redis SETNX distributed lock (cross-instance race protection)
   ├─ JPA @Version optimistic lock (same-JVM race protection, up to 3 retries)
   │
   ├── ✅ Stock OK
   │       └── publish OrderStatusEvent { status=CONFIRMED } → order-status-updated
   │
   └── ❌ Stock FAIL (or contention exhausted)
           └── publish InventoryFailedEvent { failureReason } → inventory-failed
                              │
                              ▼
              Kafka: inventory-failed (3 partitions)
                              │ consumed by  [group: order-service-saga-group]
                              ▼
                     Order Service — InventoryFailedConsumer
                     1. order.status = FAILED  (compensating transaction → MongoDB)
                     2. publish OrderStatusEvent { status=FAILED } → order-status-updated
                              │
                              ▼
              Kafka: order-status-updated (3 partitions)
                              │ consumed by  [group: notification-service-group]
                              ▼
                 Notification Service (:8085)
                 → sends CONFIRMED / FAILED / SHIPPED / DELIVERED emails (Spring Mail)
                 → logs to notification_db
                 → failed sends → DLT (order-status-updated.DLT) after 3 retries
```

**Why choreography-based?** Each service reacts to Kafka events independently — no central
orchestrator. This keeps services decoupled, resilient to individual outages (Kafka retains
messages), and horizontally scalable (3 partitions per topic → up to 3 parallel consumer
instances per service).

---

## 🧩 Services Overview

| Service | Port | Database | Eureka Name | Role |
|---|---|---|---|---|
| **Eureka Server** | 8761 | — | — | Service registry & discovery |
| **API Gateway** | 8080 | — | `API-GATEWAY` | Single entry point: JWT validation, rate limiting, response caching, routing |
| **User Service** | 8081 | MySQL `user_db` | `USER-SERVICE` | Registration, login (JWT), logout, profile, token blacklist |
| **Product Service** | 8082 | MySQL `product_db` | `PRODUCT-SERVICE` | Product/category CRUD, search, pagination, RBAC |
| **Order Service** | 8083 | MongoDB `orderdb` | `ORDER-SERVICE` | Order placement, Feign calls to User/Product, Circuit Breaker (Resilience4j), Kafka producer (`order-events`), Saga compensation consumer (`inventory-failed` → marks order FAILED + publishes to `order-status-updated`) |
| **Inventory Service** | 8084 | MySQL `inventory_db` | `INVENTORY-SERVICE` | Stock check/decrement, two-layer locking, Saga participant |
| **Notification Service** | 8085 | MySQL `notification_db` | `NOTIFICATION-SERVICE` | Terminal Kafka consumer — sends order status emails |
| **AI Service** *(optional)* | 8086 | PGVector | `AI-SERVICE` | Chatbot (function calling), semantic search, recommendations, AI-generated content |

---

## 🚀 Quick Start

### Prerequisites

| Tool | Version |
|---|---|
| Java | 21+ |
| Maven | 3.9+ |
| MySQL | 8.0 |
| MongoDB | 6.0 |
| Redis | 7 |
| Kafka + Zookeeper | 3.x |

### 1. Start Infrastructure

```powershell
cd "C:\Private\Spring Boot Project\Online Shopping"
docker-compose up -d    # MySQL, MongoDB, Redis, Kafka, Zookeeper
```

### 2. Create Databases

```sql
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS product_db;
CREATE DATABASE IF NOT EXISTS inventory_db;
CREATE DATABASE IF NOT EXISTS notification_db;
-- MongoDB 'orderdb' auto-created on first write
```

### 3. Startup Order (important — dependencies matter)

```
1. MySQL + Redis + Kafka + Zookeeper
2. Eureka Server        (:8761)
3. User Service         (:8081)
4. Product Service      (:8082)
5. Order Service        (:8083)
6. Inventory Service    (:8084)
7. Notification Service (:8085)
8. AI Service           (:8086)  ← optional, requires OPENAI_API_KEY or Ollama
9. API Gateway          (:8080)  ← always start LAST
```

```powershell
cd eureka-server;        mvn spring-boot:run   # Terminal 1
cd user-service;         mvn spring-boot:run   # Terminal 2
cd product-service;      mvn spring-boot:run   # Terminal 3
cd order-service;        mvn spring-boot:run   # Terminal 4
cd inventory-service;    mvn spring-boot:run   # Terminal 5
cd notification-service; mvn spring-boot:run   # Terminal 6
$env:OPENAI_API_KEY="sk-your-key"
cd ai-service;            mvn spring-boot:run   # Terminal 7 (optional)
cd api-gateway;           mvn spring-boot:run   # Terminal 8 — LAST
```

### 4. Verify

| URL | Expected |
|---|---|
| `http://localhost:8761` | Eureka dashboard — all services registered |
| `http://localhost:8080/actuator/health` | `{"status":"UP"}` |
| `http://localhost:8080/actuator/gateway/routes` | All routes listed |

---

## 🔐 Security Architecture — Centralized at the Gateway

JWT validation happens **once**, at the API Gateway. Downstream services **trust forwarded
headers** and never re-parse tokens.

```
Client --Bearer JWT--> API Gateway
                          │ JwtAuthFilter:
                          │  1. verify signature
                          │  2. check Redis blacklist
                          │  3. extract claims (userId, role)
                          ▼
                  X-User-Id / X-Username / X-User-Role
                          │
                          ▼
              Downstream Service GatewayAuthFilter
                  → reads headers, sets SecurityContext
                  → SecurityConfig enforces hasRole("ADMIN") etc.
```

- **All services must share the identical `jwt.secret`.**
- Every service (except the Gateway) implements the same `GatewayAuthFilter` +
  `SecurityConfig` pattern: reads `X-User-Id`, `X-Username`, `X-User-Role`; GET is public,
  write operations (`POST`/`PUT`/`DELETE`) require `ROLE_ADMIN` where applicable.
- **Exception:** User Service's `logout` endpoint still reads the raw `Authorization` header
  directly, because it needs the literal token string to blacklist it in Redis —
  `X-Username` alone isn't enough for that operation.
- Inventory Service and Notification Service do **not** validate JWTs at all; identity is
  fully trusted via forwarded headers (Inventory), or the service is a pure async Kafka
  consumer with no inbound trust decision to make (Notification's Kafka listener).

### Required JWT Claims

```json
{ "sub": "john_doe", "userId": "101", "role": "ADMIN", "email": "john@example.com", "iat": ..., "exp": ... }
```

---

## 🛣️ API Gateway Routing

All client requests go through `http://localhost:8080`. **Route order matters** — specific
routes must be declared before catch-all routes.

| Route ID | Gateway Path | Method | JWT? | Forwards To |
|---|---|---|:---:|---|
| `user-service-user-endpoints` | `/api/auth/user/**` | ANY | ✅ | `USER-SERVICE /api/auth/user/**` |
| `user-service-logout` | `/api/auth/logout` | POST | ✅ | `USER-SERVICE /api/auth/logout` |
| `user-service-auth` | `/api/auth/**` | ANY | ❌ | `USER-SERVICE /api/auth/**` |
| `product-service-public` | `/api/products/**` | GET | ❌ | `PRODUCT-SERVICE /products/**` |
| `product-service-protected` | `/api/products/**` | POST/PUT/DELETE | ✅ | `PRODUCT-SERVICE /products/**` |
| `order-service` | `/api/orders/**` | ANY | ✅ | `ORDER-SERVICE /order/**` |
| `inventory-service-public` | `/api/inventory/**` | GET | ❌ | `INVENTORY-SERVICE /inventory/**` |
| `inventory-service-protected` | `/api/inventory/**` | POST/PUT/DELETE | ✅ (ADMIN) | `INVENTORY-SERVICE /inventory/**` |
| `notification-service` | `/api/notifications/**` | ANY | ✅ | `NOTIFICATION-SERVICE /notifications/**` |
| `ai-service-public` | `/api/ai/search/**`, `/api/ai/recommendations/**` | GET | ❌ | `AI-SERVICE /ai/**` |
| `ai-service-protected` | `/api/ai/**` | POST/PUT/DELETE | ✅ | `AI-SERVICE /ai/**` |

> Notification Service's REST history endpoints go through the Gateway, but its **Kafka
> consumer runs independently** and never touches the Gateway.

### Rate Limits (Sliding Window via Redis + Lua)

| Endpoint | Limit/min | Reason |
|---|---|---|
| `POST /api/auth/login` | 5 | Brute-force protection |
| `POST /api/auth/register` | 10 | Fake account prevention |
| `/api/orders/**` | 20 | Payment protection |
| `/api/products/**` | 150 | Read-heavy traffic |
| `/api/users/**` | 50 | Profile operations |
| Everything else | 100 | Default |

---

## 📡 Complete API Reference

> **Base URL:** `http://localhost:8080` (always via API Gateway)

### 👤 User Service — Auth & Profile

| Method | Endpoint | Auth | Body |
|---|---|:---:|---|
| POST | `/api/auth/register` | ❌ | `{"username","email","password","role"}` |
| POST | `/api/auth/login` | ❌ | `{"username","password"}` → `{"token": "..."}` |
| POST | `/api/auth/logout` | ✅ | — (blacklists token in Redis) |
| GET | `/api/auth/user/{id}` | ✅ | — |
| GET | `/api/auth/user/profile` | ✅ | — (identity from `X-Username`) |
| PUT | `/api/auth/user/profile` | ✅ | `{"email","password"}` (all optional) |

### 📦 Product Service — Catalog

| Method | Endpoint | Auth | Body |
|---|---|:---:|---|
| GET | `/api/products?page=&size=&sort=` | ❌ | — |
| GET | `/api/products/{id}` | ❌ | — |
| GET | `/api/products/search?query=` | ❌ | — |
| POST | `/api/products` | ✅ ADMIN | `{"name","description","price","stock","categoryId"}` |
| PUT | `/api/products/{id}` | ✅ ADMIN | same as POST |
| DELETE | `/api/products/{id}` | ✅ ADMIN | — |

### 🛒 Order Service — Orders

| Method | Endpoint | Auth | Body |
|---|---|:---:|---|
| POST | `/api/orders/newOrder` | ✅ | `{"userId","items":[{"productId","quantity"}]}` — `userEmail` resolved internally via Feign |
| GET | `/api/orders/{mongoId}` | ✅ | — |
| GET | `/api/orders/user/{userId}` | ✅ | — |
| PUT | `/api/orders/{id}/status?status=CONFIRMED` | ✅ | — |

### 📊 Inventory Service — Stock

| Method | Endpoint | Auth | Body |
|---|---|:---:|---|
| GET | `/api/inventory/{productId}` | ❌ | — |
| GET | `/api/inventory` | ✅ ADMIN | — (list all) |
| POST | `/api/inventory/init` | ✅ ADMIN | `{"productId","productName","quantity"}` |
| PUT | `/api/inventory/{productId}/restock` | ✅ ADMIN | `{"quantity"}` |

### 🔔 Notification Service — History

| Method | Endpoint | Auth | Description |
|---|---|:---:|---|
| GET | `/api/notifications/my` | ✅ | My notification history (from `X-User-Id`) |
| GET | `/api/notifications/order/{orderId}` | ✅ | Logs for a specific order |

> Kafka-driven notifications are **not** triggered via Postman — they fire automatically when
> Order Service publishes to `order-events`, which cascades through Inventory → Notification.

### 🤖 AI Service *(optional, Spring AI)*

| Method | Endpoint | Auth | Body |
|---|---|:---:|---|
| POST | `/api/ai/chat` | ✅ | `{"message","sessionId"}` |
| POST | `/api/ai/chat/stream` | ✅ | `{"message","sessionId"}` (SSE) |
| GET | `/api/ai/search?q=&limit=` | ❌ | Semantic product search |
| GET | `/api/ai/recommendations/{id}?limit=` | ❌ | — |
| POST | `/api/ai/generate/description` | ✅ ADMIN | `{"productName","category","price"}` |
| POST | `/api/ai/generate/notification` | ✅ ADMIN | `{"status","userName","orderId","totalAmount"}` |

### 🔍 Health & Debug

| URL | Description |
|---|---|
| `http://localhost:8761` | Eureka dashboard |
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8080/actuator/gateway/routes` | Active routes |
| `http://localhost:8084/actuator/prometheus` | Inventory metrics |

---

## 🔗 Inter-Service Communication (Feign)

```
Order Service (:8083)
   ├── UserClient    → GET user-service /api/auth/user/{id}
   └── ProductClient → GET product-service /api/products/{id}
```

| From ↓ / To → | User Service | Product Service | Order Service |
|:-:|:-:|:-:|:-:|
| **Order Service** | ✅ Feign (UserClient) | ✅ Feign (ProductClient) | — |
| **Product Service** | optional Feign (admin validation) | — | optional Feign (recently ordered) |
| **User Service** | — | — | optional Feign (order history) |
| **API Gateway** | ✅ Route | ✅ Route | ✅ Route |

> ⚠️ **Known field mismatch:** Order Service's `UserResponse` expects `{"id","name","email"}` but User Service returns `{"id","username","role"}` — the `name` vs `username` field diverges. The `email` field used by the Saga (`userEmail` on `OrderEvent`) requires User Service to include it in the response; verify this is present before end-to-end testing the Saga failure path.

---

## 🔄 Kafka Topics Summary

| Topic | Partitions | Producer(s) | Consumer(s) | Purpose |
|---|---|---|---|---|
| `order-events` | 3 | Order Service | **Inventory Service** | New order → check/reserve stock |
| `order-status-updated` | 3 | Inventory Service (CONFIRMED) + Order Service (FAILED/SHIPPED/DELIVERED) | **Notification Service** | All status changes → email |
| `inventory-failed` | 3 | Inventory Service | Order Service (Saga compensation) | Triggers compensating transaction |

**Consumer groups:**

| Group | Service | Topic | Commits after |
|---|---|---|---|
| `inventory-service-group` | Inventory Service | `order-events` | Stock reservation + idempotency row saved |
| `order-service-saga-group` | Order Service | `inventory-failed` | Order marked FAILED in MongoDB + status event published |
| `notification-service-group` | Notification Service | `order-status-updated` | Email sent + notification log saved |

All consumers use `enable-auto-commit=false` with `ack-mode=manual` — the offset is committed
only after the DB write (and publish, where applicable) succeeds. A crash before `ack.acknowledge()`
causes Kafka to redeliver the message; idempotency checks prevent double-processing.

### Race-Condition Defense (Inventory Service — Two-Layer Locking)

| Layer | Mechanism | Scope | Handles |
|---|---|---|---|
| Primary | JPA `@Version` (optimistic lock) | Same JVM instance | Two threads updating the same row |
| Fallback | Redis `SETNX` distributed lock (TTL 10s) | Cross-instance | Two service instances decrementing the same product |
| Safety | Lock TTL auto-expiry | — | Service crash mid-lock → auto-recovers, no deadlock |

Idempotency is enforced independently via a `ProcessedEvent` table keyed by `orderId`, so
Kafka's at-least-once redelivery never double-decrements stock.

---

### Dead Letter Topic (Notification Service — Failed Email Handling)

When an email send fails after retries, the message is routed to a DLT instead of being
silently dropped or retried forever.

```
order-status-updated (main topic)
        │
        ▼
Notification Service consumer
        │
        ├── ✅ Email sent      → ack.acknowledge() → offset committed
        │
        └── ❌ Email failed (retried 3× with exponential backoff: 1s → 2s → 4s)
                    │
                    ▼
          order-status-updated.DLT
                    │
                    ├── notification_logs row saved with status=FAILED + failureReason
                    └── visible via GET /api/notifications/order/{orderId} for ops review
```

**`notification-service/application.properties`:**

```properties
spring.kafka.listener.ack-mode=manual
spring.kafka.retry.topic.enabled=true
spring.kafka.retry.topic.attempts=3
spring.kafka.retry.topic.delay=1000
spring.kafka.retry.topic.multiplier=2
spring.kafka.retry.topic.max-delay=10000
```

> 3 retries covers transient SMTP failures (timeouts, rate limits). Permanent failures
> (invalid address, suspended account) exhaust retries quickly and land in the DLT — no
> benefit retrying those more times.

---

## ⚡ Circuit Breaker (Order Service — Resilience4j)

Order Service wraps both Feign calls in independent circuit breakers so a failing upstream
trips its own breaker without affecting the other.

### State Machine

```
CLOSED (normal)
    │  ≥50% failure rate over last 10 calls (min 5 calls required)
    │  OR ≥50% slow calls (> 2s threshold)
    ▼
OPEN (tripped) — fallback fires immediately, no network call made
    │  after waitDuration (30s)
    ▼
HALF-OPEN — sends 3 probe calls
    ├── probes OK  → back to CLOSED
    └── probes fail → back to OPEN
```

**`order-service/application.properties`:**

```properties
# Circuit Breaker — User Service
resilience4j.circuitbreaker.instances.userService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.userService.minimumNumberOfCalls=5
resilience4j.circuitbreaker.instances.userService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.userService.slowCallDurationThreshold=2s
resilience4j.circuitbreaker.instances.userService.slowCallRateThreshold=50
resilience4j.circuitbreaker.instances.userService.waitDurationInOpenState=30s
resilience4j.circuitbreaker.instances.userService.permittedNumberOfCallsInHalfOpenState=3

# Circuit Breaker — Product Service
resilience4j.circuitbreaker.instances.productService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.productService.minimumNumberOfCalls=5
resilience4j.circuitbreaker.instances.productService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.productService.slowCallDurationThreshold=2s
resilience4j.circuitbreaker.instances.productService.slowCallRateThreshold=50
resilience4j.circuitbreaker.instances.productService.waitDurationInOpenState=30s
resilience4j.circuitbreaker.instances.productService.permittedNumberOfCallsInHalfOpenState=3

# Actuator — expose live circuit breaker state
management.endpoints.web.exposure.include=health,info,circuitbreakers,metrics
management.endpoint.health.show-details=always
management.health.circuitbreakers.enabled=true
```

When OPEN, the fallback returns HTTP **503** with no internal service names exposed:

```json
{ "timestamp": "...", "status": 503, "error": "Service Unavailable",
  "message": "The service is temporarily unavailable. Please try again shortly.",
  "retryAfter": 30 }
```

**Monitor live state:**
```bash
GET http://localhost:8083/actuator/health              # CLOSED / OPEN / HALF_OPEN
GET http://localhost:8083/actuator/metrics/resilience4j.circuitbreaker.state
```

---

## 🗄️ Data Stores

### MySQL

| DB | Owner | Key Tables |
|---|---|---|
| `user_db` | User Service | `users` |
| `product_db` | Product Service | `product`, `category` |
| `inventory_db` | Inventory Service | `inventory` (`@Version`), `processed_events` |
| `notification_db` | Notification Service | `notification_logs` |

### MongoDB

| DB | Owner | Shape |
|---|---|---|
| `orderdb` | Order Service | `{ _id, userId, userEmail, items[], totalAmount, status, createdAt }` — `userEmail` populated at order creation via Feign call to User Service; required for Saga failure email path |

### Redis (Port 6379)

| Key Pattern | Owner | TTL | Purpose |
|---|---|---|---|
| `rate_limit:{user/ip}:{path}` | API Gateway | 70s | Sliding-window counters |
| `token_blacklist:{token}` | API Gateway | 24h | Invalidated JWTs (gateway-level) |
| `cache:{path}?{query}` | API Gateway | 5 min | GET response cache |
| `blacklist:{token}` | User Service | token expiry | Service-level token blacklist |
| `lock:inventory:{productId}` | Inventory Service | 10s | Distributed SETNX stock lock |
| `product:*`, `category:*` | Product Service *(planned)* | 5 / 30 min | Product/category cache |

---

## ✅ Cross-Service Implementation Status

| Component | Gateway | User | Product | Order | Inventory | Notification |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Core endpoints | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Eureka registration | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `GatewayAuthFilter` | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| `SecurityFilterChain` | — | ✅ | ✅ | ✅ | ✅ | ✅ |
| JWT secret aligned | ✅ | ✅ | ✅ | ✅ | ✅ | n/a |
| Redis cache | ✅ | ❌ | ❌ | ❌ | ✅ (lock only) | — |
| Kafka | — | — | — | ✅ Producer + ✅ Saga consumer | ✅ Consumer + ✅ Producer | ✅ Consumer (terminal) |
| Circuit breaker | — | — | — | ✅ userService + productService | — | — |
| Dead letter topic | — | — | — | — | — | ✅ order-status-updated.DLT |
| `userEmail` on Order/Event | — | — | — | ✅ | ✅ (reads from event) | ✅ (reads from event) |

---

## ⚠️ Known Issues & Outstanding Work

### 🟡 Important

- **`UserResponse` field mismatch** — Order Service's `UserResponse` DTO has a `name` field but User Service returns `username`. The `email` field (needed by the Saga failure path to send failure emails) must also be present in User Service's response; verify before end-to-end testing the failure flow.
- **No Redis caching on Product/Order reads** — `@Cacheable` recommended for `GET /products/{id}` and `GET /orders/{id}` to reduce DB load under traffic.
- **Feign calls don't propagate `X-User-*` headers** — add a shared `FeignConfig` `RequestInterceptor` so downstream services receive identity context on inter-service calls.

### 🟢 Nice to Have

- Distributed tracing (Zipkin/Micrometer) across the Kafka → email latency path.
- Centralized configuration via Spring Cloud Config Server.
- Notification inbox pagination, SMS/push channels, user notification preferences.
- Spring AI: PGVector sync from Product Service via `product-events` topic.

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Gateway | Spring Cloud Gateway, Netty |
| Discovery | Netflix Eureka |
| Auth | JWT (JJWT 0.12.6), Spring Security |
| REST Clients | OpenFeign, Resilience4j (Circuit Breaker) |
| Databases | MySQL 8 (JPA/Hibernate), MongoDB 6 |
| Cache / Locking / Rate Limit | Redis 7 (Lettuce, Lua scripts, SETNX) |
| Messaging | Apache Kafka (manual-ack, 3-partition topics, DLT) |
| AI *(optional)* | Spring AI, PGVector, OpenAI/Ollama |
| Build | Maven, Java 21 |
| Spring Boot / Cloud | 3.2.5 / 2023.0.1 |

---

## 🧪 End-to-End Postman Walkthrough

```
1. POST /api/auth/register          → create user
2. POST /api/auth/login              → get JWT
3. POST /api/inventory/init (ADMIN)  → { "productId":1, "productName":"Laptop", "quantity":10 }
4. POST /api/orders/newOrder         → { "userId":1, "items":[{"productId":1,"quantity":2}] }
5. GET  /api/notifications/my        → expect ORDER_CONFIRMED entry

Failure path:
6. POST /api/orders/newOrder         → quantity greater than stock (e.g. 9999)
7. GET  /api/notifications/my        → expect ORDER_FAILED entry with failureReason
```

---

## 📂 Repository Layout

```
Online Shopping/
├── eureka-server/                  # Service Registry (:8761)
├── api-gateway/                    # API Gateway (:8080)
├── user-service/                   # Auth & Profile (:8081) — MySQL
├── product-service/                # Catalog (:8082) — MySQL
├── order-service/                  # Orders + Saga producer/compensator (:8083) — MongoDB
├── inventory-service/              # Stock + Saga participant (:8084) — MySQL + Redis
├── notification-service/           # Terminal Kafka consumer, email (:8085) — MySQL
├── ai-service/                     # Spring AI chatbot/search (:8086) — PGVector [optional]
├── docker-compose.yml              # MySQL, MongoDB, Redis, Kafka, Zookeeper
└── README.md                       # This file
```

---

## 📄 License

This project is for learning and demonstration purposes.