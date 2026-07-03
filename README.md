# 🛒 Online Shopping — Microservices E-Commerce Backend

> Consolidated project documentation — merged from all service READMEs and the architecture design doc.
> **Last Updated:** June 25, 2026 · **Spring Boot:** 3.2.5 · **Spring Cloud:** 2023.0.1 · **Java:** 21

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
   │ publish OrderEvent(status=PLACED)
   ▼
Kafka: order-events (3 partitions)
   │ consumed by
   ▼
Inventory Service (:8084)
   ├─ Redis SETNX distributed lock (cross-instance)
   ├─ JPA @Version optimistic lock (same JVM)
   ├─ Idempotency check (ProcessedEvent table)
   │
   ├── ✅ Stock OK  → publish CONFIRMED → order-status-updated
   └── ❌ Stock FAIL → publish → inventory-failed
                              │
                              ▼
                     Order Service (SAGA compensation)
                     sets order.status = FAILED
                     publish FAILED → order-status-updated
                              │
                              ▼
              Kafka: order-status-updated (3 partitions)
                              │ consumed by
                              ▼
                 Notification Service (:8085)
                 → sends CONFIRMED / FAILED / SHIPPED / DELIVERED emails
                 → logs to notification_db
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
| **Order Service** | 8083 | MongoDB `orderdb` | `ORDER-SERVICE` | Order placement, Feign calls to User/Product, Kafka producer, Saga compensation consumer |
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
5. Order Service        (:8083)  ← must have inventory-failed consumer added
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
| POST | `/api/orders/newOrder` | ✅ | `{"userId","items":[{"productId","quantity"}]}` |
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

> ⚠️ **Known field mismatch:** Order Service's `UserResponse` expects `{"id","name","email"}`,
> but User Service's default entity returns `{"id","username","role"}`. Resolve by either
> adding a `name` alias/dedicated endpoint on User Service, or renaming the field on the
> Order Service DTO to `username`.

---

## 🔄 Kafka Topics Summary

| Topic | Partitions | Producer(s) | Consumer(s) | Purpose |
|---|---|---|---|---|
| `order-events` | 3 | Order Service | **Inventory Service** | New order → check/reserve stock |
| `order-status-updated` | 3 | Inventory Service (CONFIRMED) + Order Service (FAILED/SHIPPED/DELIVERED) | **Notification Service** | All status changes → email |
| `inventory-failed` | 3 | Inventory Service | Order Service (Saga compensation) | Triggers compensating transaction |

**Consumer groups:** `inventory-service-group`, `order-service-saga-group`,
`notification-service-group` — each with manual ack (`enable-auto-commit=false`), so an
offset is only committed after the corresponding DB write / email send succeeds, giving
natural at-least-once retry semantics on infrastructure failures.

### Race-Condition Defense (Inventory Service — Two-Layer Locking)

| Layer | Mechanism | Scope | Handles |
|---|---|---|---|
| Primary | JPA `@Version` (optimistic lock) | Same JVM instance | Two threads updating the same row |
| Fallback | Redis `SETNX` distributed lock (TTL 10s) | Cross-instance | Two service instances decrementing the same product |
| Safety | Lock TTL auto-expiry | — | Service crash mid-lock → auto-recovers, no deadlock |

Idempotency is enforced independently via a `ProcessedEvent` table keyed by `orderId`, so
Kafka's at-least-once redelivery never double-decrements stock.

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
| `orderdb` | Order Service | `{ _id, userId, userName, userEmail, items[], totalAmount, status, createdAt }` |

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
| `GatewayAuthFilter` | — | ⚠️ partial | ✅ | ⚠️ needs adding | ✅ | ✅ |
| `SecurityFilterChain` | — | ✅ | ✅ | ⚠️ needs adding | ✅ | ✅ |
| Redis cache | ✅ | ❌ | ❌ | ❌ | ✅ (lock only) | — |
| JWT secret aligned | ✅ | ✅ | ⚠️ mismatch | ⚠️ mismatch | ✅ | n/a |
| Kafka | — | — | — | Producer + Saga consumer | Consumer + Producer | Consumer (terminal) |

---

## ⚠️ Known Issues & Outstanding Work

### 🔴 Critical

1. **JWT secret mismatch** — Product Service and Order Service currently use a different
   `jwt.secret` than Gateway/User Service. All services must use:
   ```properties
   jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
   ```
2. **Order Service has no `SecurityFilterChain`** — currently defaults to Basic Auth; needs
   the same `GatewayAuthFilter` + `SecurityConfig` pattern as Product/Inventory/Notification.
3. **User Service token blacklist is in-memory** (`ConcurrentHashMap`) — breaks with multiple
   instances. Must migrate to `RedisTokenBlacklistService`.
4. **User Service re-parses JWT** in `getUserProfile`/`updateUserProfile` instead of trusting
   the Gateway's `X-Username` header — violates the centralized-auth architecture (logout is
   the one correct exception, since it needs the raw token to blacklist it).
5. **Order Service must be updated** to support Inventory Service's saga: add `userEmail` to
   `OrderEvent`, add `InventoryFailedEvent`/`OrderStatusEvent` DTOs, add the
   `InventoryFailedConsumer` Kafka listener (see Inventory Service section).
6. **Product Service duplicate endpoint** — `POST /products/addproduct` duplicates
   `POST /products`; remove `saveProduct()`.

### 🟡 Important

- Gateway → service path mismatches must use `RewritePath` filters (`/api/products/**` →
  `/products/**`, `/api/orders/**` → `/order/**`) — already applied for Product/Order/Inventory.
- `UserResponse` field mismatch (`name` vs `username`) between Order Service and User Service.
- No Redis caching yet on Product/Order Service reads (`@Cacheable` recommended).
- No circuit breaker (Resilience4j) around Feign calls in Order Service.
- No dead-letter topic for Notification Service's failed email sends.
- Feign calls don't currently propagate `X-User-*` headers downstream — add a shared
  `FeignConfig` `RequestInterceptor`.

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
| REST Clients | OpenFeign |
| Databases | MySQL 8 (JPA/Hibernate), MongoDB 6 |
| Cache / Locking / Rate Limit | Redis 7 (Lettuce, Lua scripts, SETNX) |
| Messaging | Apache Kafka (manual-ack, 3-partition topics) |
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