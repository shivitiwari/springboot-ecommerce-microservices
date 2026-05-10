# 🛒 Online Shopping — Microservices E-Commerce Backend

> Microservices-based e-commerce backend built with **Spring Boot 3.2**, **Spring Cloud Gateway**, **Eureka**, **Redis**, **Kafka**, and **MongoDB/MySQL**.

---

## 📐 Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           CLIENT LAYER                                   │
│               (Web Browser / Mobile App / Postman / curl)               │
└──────────────────────────────┬───────────────────────────────────────────┘
                               │ HTTP
                               ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    API GATEWAY  (Spring Cloud Gateway)                    │
│                              Port: 8080                                  │
├──────────────────────────────────────────────────────────────────────────┤
│  ┌───────────────┐   ┌───────────────┐   ┌───────────────┐             │
│  │ Rate Limiting  │ → │ JWT Auth      │ → │ Redis Cache   │             │
│  │ • Sliding      │   │ • Validate    │   │ • Cache GET   │             │
│  │   Window       │   │ • Blacklist   │   │ • 5min TTL    │             │
│  │ • Per-user/IP  │   │ • Forward     │   │ • HIT/MISS    │             │
│  │ • Lua Script   │   │   X-User-*    │   │               │             │
│  └───────────────┘   └───────────────┘   └───────────────┘             │
│                               │                                          │
│            ┌──────────────────┼──────────────────┐                      │
│            │                  │                  │                      │
│      /api/auth/**      /api/products/**    /api/orders/**               │
│            │                  │                  │                      │
│      lb://USER-SERVICE  lb://PRODUCT-SERVICE  lb://ORDER-SERVICE       │
└────────────┼──────────────────┼──────────────────┼───────────────────────┘
             │                  │                  │
             ▼                  ▼                  ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    EUREKA SERVER  (Service Registry)                      │
│                              Port: 8761                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  ┌─────────────┐        │
│  │API-GATEWAY│  │USER-SVC  │  │PRODUCT-SVC   │  │ORDER-SVC    │        │
│  │ :8080     │  │ :8081    │  │ :8082        │  │ :8083       │        │
│  └──────────┘  └──────────┘  └──────────────┘  └─────────────┘        │
│         Heartbeat ♡ every 30s  │  Instance Health  │  Load Balancing    │
└──────────────────────────────────────────────────────────────────────────┘
             │                  │                  │
             ▼                  ▼                  ▼
┌────────────────┐  ┌────────────────┐  ┌────────────────┐
│ USER SERVICE   │  │PRODUCT SERVICE │  │ ORDER SERVICE  │
│ Port: 8081     │  │ Port: 8082     │  │ Port: 8083     │
│ MySQL (user_db)│  │ MySQL          │  │ MongoDB        │
│                │  │ (product_db)   │  │ (orderdb)      │
├────────────────┤  ├────────────────┤  ├────────────────┤
│• Register      │  │• Product CRUD  │  │• Place Order   │
│• Login (JWT)   │  │• Category Mgmt │  │• Order Status  │
│• Logout        │  │• Search        │  │• Kafka Producer│
│• User Profile  │  │• Pagination    │  │• Feign Clients │
│• Token Blacklst│  │• RBAC (Admin)  │  │  → UserClient  │
└───────┬────────┘  └───────┬────────┘  │  → ProductClnt │
        │                   │           └──┬─────┬───────┘
        │                   │              │     │
        ▼                   ▼              ▼     ▼
   ┌─────────┐         ┌─────────┐   ┌──────┐ ┌──────┐
   │  MySQL  │         │  MySQL  │   │Kafka │ │Feign │
   │ user_db │         │product_db│  │Events│ │Calls │
   └─────────┘         └─────────┘   └──────┘ └──────┘
                                          │
                       ┌──────────────────┘
                       ▼
              ┌─────────────────┐
              │      REDIS      │
              │    Port: 6379   │
              ├─────────────────┤
              │ rate_limit:*    │  ← Gateway rate counters
              │ token_blacklist:│  ← Invalidated JWTs
              │ cache:*         │  ← Response cache
              └─────────────────┘
```

---

## 🧩 Services Overview

| Service | Port | Database | Eureka Name | Description |
|---------|------|----------|-------------|-------------|
| **Eureka Server** | 8761 | — | — | Service registry & discovery |
| **API Gateway** | 8080 | — | `API-GATEWAY` | Single entry point, JWT validation, rate limiting, caching |
| **User Service** | 8081 | MySQL `user_db` | `USER-SERVICE` | Authentication, user profiles, token blacklist |
| **Product Service** | 8082 | MySQL `product_db` | `PRODUCT-SERVICE` | Product CRUD, search, pagination, RBAC |
| **Order Service** | 8083 | MongoDB `orderdb` | `ORDER-SERVICE` | Order management, Feign inter-service calls, Kafka events |

---

## 🚀 Quick Start

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21+ | Runtime |
| Maven | 3.9+ | Build |
| MySQL | 8.0 | User & Product data |
| MongoDB | 6.0 | Order data |
| Redis | 7 | Caching, rate limiting, token blacklist |
| Kafka | 3.x | Order event streaming |

### 1. Start Infrastructure

```powershell
# Docker Compose (recommended)
cd "C:\Private\Spring Boot Project\Online Shopping"
docker-compose up -d    # MySQL, MongoDB, Redis, Kafka, Zookeeper
```

Or start each manually:

```
MySQL   → localhost:3306
MongoDB → localhost:27017
Redis   → localhost:6379
Kafka   → localhost:9092
```

### 2. Create Databases

```sql
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS product_db;
-- MongoDB 'orderdb' is auto-created on first write
```

### 3. Start Services (in order)

```powershell
# Terminal 1 — Eureka Server (must start FIRST)
cd eureka-server; mvn spring-boot:run

# Wait ~15s for Eureka to be ready, then:

# Terminal 2 — API Gateway
cd api-gateway; mvn spring-boot:run

# Terminal 3 — User Service
cd user-service; mvn spring-boot:run

# Terminal 4 — Product Service
cd product-service; mvn spring-boot:run

# Terminal 5 — Order Service
cd order-service; mvn spring-boot:run
```

### 4. Verify Everything

| URL | Expected |
|-----|----------|
| http://localhost:8761 | Eureka dashboard — all 4 services registered |
| http://localhost:8080/actuator/health | `{"status":"UP"}` |
| http://localhost:8080/actuator/gateway/routes | All 6 routes listed |

---

## 🔐 Security Architecture

JWT validation happens **once at the API Gateway**. Downstream services **trust gateway headers** — no redundant token parsing.

```
Client                       API Gateway                  Downstream Service
  │                              │                               │
  │  Authorization: Bearer <JWT> │                               │
  │  ───────────────────────────►│                               │
  │                              │                               │
  │                        ┌─────┴──────┐                        │
  │                        │ JwtAuth    │                        │
  │                        │ Filter     │                        │
  │                        │ 1. Verify  │                        │
  │                        │    signature│                        │
  │                        │ 2. Check   │                        │
  │                        │    Redis   │                        │
  │                        │    blacklst│                        │
  │                        │ 3. Extract │                        │
  │                        │    claims  │                        │
  │                        └─────┬──────┘                        │
  │                              │                               │
  │                              │  X-User-Id: 123              │
  │                              │  X-Username: john             │
  │                              │  X-User-Role: ADMIN           │
  │                              │──────────────────────────────►│
  │                              │                               │
  │                              │              GatewayAuthFilter reads headers
  │                              │              Sets SecurityContext
  │                              │              Enforces hasRole("ADMIN")
  │                              │                               │
  │◄─────────────────────────────│◄──────────────────────────────│
```

### JWT Token Structure

```json
{
  "sub": "john_doe",
  "userId": "101",
  "role": "ADMIN",
  "email": "john@example.com",
  "iat": 1715300000,
  "exp": 1715386400
}
```

> ⚠️ **All services must share the identical `jwt.secret`** in their `application.properties`.

---

## 🛣️ API Gateway Routing

All client requests go through `http://localhost:8080`. Routes are defined in `FilterConfig.java`.

### Route Table

| Route ID | Gateway Path | Method | JWT | Forwards To |
|----------|-------------|--------|:---:|-------------|
| `user-service-user-endpoints` | `/api/auth/user/**` | ANY | ✅ | `USER-SERVICE /api/auth/user/**` |
| `user-service-logout` | `/api/auth/logout` | POST | ✅ | `USER-SERVICE /api/auth/logout` |
| `user-service-auth` | `/api/auth/**` | ANY | ❌ | `USER-SERVICE /api/auth/**` |
| `product-service-public` | `/api/products/**` | GET | ❌ | `PRODUCT-SERVICE /products/**` |
| `product-service-protected` | `/api/products/**` | POST/PUT/DEL | ✅ | `PRODUCT-SERVICE /products/**` |
| `order-service` | `/api/orders/**` | ANY | ✅ | `ORDER-SERVICE /order/**` |

> ⚠️ **Route order matters!** Specific routes (`/api/auth/user/**`, `/api/auth/logout`) must come before the catch-all (`/api/auth/**`).

### Rate Limits (Sliding Window via Redis + Lua)

| Endpoint | Limit/min | Reason |
|----------|-----------|--------|
| `/api/auth/login` | 5 | Brute-force protection |
| `/api/auth/register` | 10 | Fake account prevention |
| `/api/orders/**` | 20 | Payment protection |
| `/api/products/**` | 150 | Read-heavy traffic |
| `/api/users/**` | 50 | Profile operations |
| Everything else | 100 | Default |

---

## 📡 API Endpoints Reference

> **Base URL:** `http://localhost:8080` (always through API Gateway)

### 👤 User Service — Authentication & Profile

| Method | Endpoint | Auth | Request Body | Response |
|--------|----------|:----:|-------------|----------|
| `POST` | `/api/auth/register` | ❌ | `{"username":"john","email":"j@j.com","password":"John@123","role":"USER"}` | `"User Successfully Registered"` |
| `POST` | `/api/auth/login` | ❌ | `{"username":"john","password":"John@123"}` | `{"token":"eyJhb..."}` |
| `POST` | `/api/auth/logout` | ✅ | — | `"Logged out successfully"` |
| `GET`  | `/api/auth/user/{id}` | ✅ | — | `{"id":1,"name":"john","email":"j@j.com"}` |
| `GET`  | `/api/auth/user/profile` | ✅ | — | Full user object |
| `PUT`  | `/api/auth/user/profile` | ✅ | `{"email":"new@j.com","password":"newpass"}` | Updated user object |

### 📦 Product Service — Catalog Management

| Method | Endpoint | Auth | Request Body | Response |
|--------|----------|:----:|-------------|----------|
| `GET`  | `/api/products` | ❌ | — | Paginated products |
| `GET`  | `/api/products?page=0&size=10` | ❌ | — | Paginated products |
| `GET`  | `/api/products/{id}` | ❌ | — | Single product |
| `GET`  | `/api/products/search?query=laptop` | ❌ | — | Search results (paginated) |
| `POST` | `/api/products` | ✅ ADMIN | `{"name":"Laptop","description":"...","price":999.99,"stock":50,"categoryId":1}` | `201 Created` |
| `PUT`  | `/api/products/{id}` | ✅ ADMIN | Same as POST | `204 No Content` |
| `DELETE` | `/api/products/{id}` | ✅ ADMIN | — | `204 No Content` |

### 🛒 Order Service — Order Management

| Method | Endpoint | Auth | Request Body | Response |
|--------|----------|:----:|-------------|----------|
| `POST` | `/api/orders/newOrder` | ✅ | `{"userId":1,"items":[{"productId":1,"quantity":2}]}` | `"Order created successfully"` |
| `GET`  | `/api/orders/{mongoId}` | ✅ | — | Order details |
| `GET`  | `/api/orders/user/{userId}` | ✅ | — | User's latest order |
| `PUT`  | `/api/orders/{id}/status?status=CONFIRMED` | ✅ | — | `"Order status updated to CONFIRMED"` |

### 🔍 Health & Debug

| URL | Description |
|-----|-------------|
| `http://localhost:8761` | Eureka Dashboard |
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8080/actuator/gateway/routes` | All registered routes |

---

## 🔗 Inter-Service Communication (Feign)

Order Service uses **OpenFeign** to call User and Product services through Eureka service discovery.

```
                    Order Service (:8083)
                          │
           ┌──────────────┴──────────────┐
           │                             │
           ▼                             ▼
     UserClient (Feign)           ProductClient (Feign)
     GET user-service              GET product-service
     /api/auth/user/{id}           /api/products/{id}
           │                             │
           ▼                             ▼
     Validates user exists         Validates product exists
     Gets: id, name, email        Gets: id, name, price
```

### Communication Matrix

| From ↓ / To → | User Service | Product Service | Order Service |
|:-:|:-:|:-:|:-:|
| **API Gateway** | ✅ Route | ✅ Route | ✅ Route |
| **Order Service** | ✅ Feign (UserClient) | ✅ Feign (ProductClient) | — |
| **Product Service** | — | — | — |
| **User Service** | — | — | — |

---

## 🔄 End-to-End Request Flows

### Flow 1: Register → Login → Create Order

```
1. POST /api/auth/register
   Client → Gateway (no JWT, bypass) → User Service → MySQL → "User registered"

2. POST /api/auth/login
   Client → Gateway (no JWT, bypass) → User Service → Validate → Return JWT token

3. POST /api/orders/newOrder  (Authorization: Bearer <token>)
   Client → Gateway
            ├── RateLimitingFilter → Redis (check sliding window counter)
            ├── JwtAuthFilter      → Validate token, check blacklist
            │                        Extract userId, username, role
            │                        Forward as X-User-* headers
            └── Route to ORDER-SERVICE /order/newOrder
                    │
                    Order Service:
                    ├── Feign → User Service    → Validate user exists
                    ├── Feign → Product Service → Validate product, get price
                    ├── Save order → MongoDB
                    ├── Publish event → Kafka (order-events topic)
                    └── Return "Order created successfully"

4. POST /api/auth/logout  (Authorization: Bearer <token>)
   Client → Gateway (JWT required) → User Service → Blacklist token in Redis
```

### Flow 2: Browse Products (Public — No Auth)

```
GET /api/products?page=0&size=10
Client → Gateway
          ├── RateLimitingFilter → Redis (check rate, 150/min for products)
          ├── No JWT filter (public GET route)
          └── Route to PRODUCT-SERVICE /products?page=0&size=10
                  │
                  Product Service:
                  └── MySQL query → Paginated response → Client
```

### Flow 3: Admin Creates Product

```
POST /api/products  (Authorization: Bearer <admin-token>)
Client → Gateway
          ├── RateLimitingFilter → Redis (check rate)
          ├── JwtAuthFilter → Validate token, extract role=ADMIN
          │                    Forward: X-User-Role: ADMIN
          └── Route to PRODUCT-SERVICE /products
                  │
                  Product Service:
                  ├── GatewayAuthFilter reads X-User-Role: ADMIN
                  ├── SecurityConfig: POST /products/** → hasRole("ADMIN") ✅
                  ├── Save product → MySQL
                  └── Return 201 Created
```

---

## 🗄️ Data Stores

### MySQL — User Service (`user_db`)

```
┌─────────────────────────────┐
│ users                        │
├─────────────────────────────┤
│ id          BIGINT PK AI    │
│ username    VARCHAR UNIQUE  │
│ email       VARCHAR         │
│ password    VARCHAR (BCrypt)│
│ role        ENUM (USER/ADMIN)│
└─────────────────────────────┘
```

### MySQL — Product Service (`product_db`)

```
┌─────────────────────────────┐     ┌─────────────────────────┐
│ products                     │     │ categories               │
├─────────────────────────────┤     ├─────────────────────────┤
│ id          BIGINT PK AI    │     │ id       BIGINT PK AI   │
│ name        VARCHAR         │     │ name     VARCHAR        │
│ description TEXT             │     └─────────────────────────┘
│ price       DECIMAL         │              ▲
│ stock       INT             │              │ FK
│ category_id BIGINT ─────────┼──────────────┘
└─────────────────────────────┘
```

### MongoDB — Order Service (`orderdb`)

```json
{
  "_id": "ObjectId",
  "userId": 1,
  "userName": "john_doe",
  "userEmail": "john@example.com",
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
  "createdAt": "2026-05-10T..."
}
```

### Redis Key Patterns (Port 6379)

| Key Pattern | Service | TTL | Purpose |
|-------------|---------|-----|---------|
| `rate_limit:{user/ip}:{path}` | API Gateway | 70s | Sliding window counters |
| `token_blacklist:{token}` | API Gateway | 24h | Invalidated JWT tokens |
| `cache:{path}?{query}` | API Gateway | 5min | GET response cache |
| `blacklist:{token}` | User Service | Token expiry | Token blacklist (service level) |

### Kafka Topics

| Topic | Producer | Consumer | Event |
|-------|----------|----------|-------|
| `order-events` | Order Service | *(Not yet implemented)* | Order created / status changed |

---

## 📂 Project Structure

```
Online Shopping/
│
├── eureka-server/                  # Service Registry (:8761)
│
├── api-gateway/                    # API Gateway (:8080)
│   └── src/main/java/.../api_gateway/
│       ├── config/FilterConfig.java           # All route definitions (Java config)
│       ├── security/JwtAuthFilter.java        # JWT validation per-route
│       ├── filter/RateLimitingFilter.java     # Sliding window + Lua (GlobalFilter)
│       ├── cache/RedisCacheFilter.java        # Response caching (GatewayFilter)
│       ├── service/TokenBlacklistService.java # Redis blacklist check
│       └── util/JwtUtil.java
│
├── user-service/                   # User & Auth (:8081)
│   └── src/main/java/.../UserService/
│       ├── controller/AuthController.java     # /api/auth/** endpoints
│       ├── entity/User.java
│       ├── security/JwtUtil.java              # JWT generation
│       ├── service/UserService.java
│       └── service/TokenBlacklistService.java
│
├── product-service/                # Product Catalog (:8082)
│   └── src/main/java/.../product_service/
│       ├── controller/ProductController.java  # /products/** endpoints
│       ├── entity/Product.java
│       ├── security/GatewayAuthFilter.java    # Reads X-User-* headers ✅
│       ├── security/SecurityConfig.java       # RBAC (Admin for CUD) ✅
│       └── service/ProductService.java
│
├── order-service/                  # Order Management (:8083)
│   └── src/main/java/.../order_service/
│       ├── controller/OrderController.java    # /order/** endpoints
│       ├── client/UserClient.java             # Feign → User Service
│       ├── client/ProductClient.java          # Feign → Product Service
│       ├── service/OrderService.java
│       └── config/KafkaConfig.java
│
├── docker-compose.yml              # Infrastructure containers
├── ARCHITECTURE.md → (in api-gateway/)  # Full integration guide
└── README.md                       # This file
```

---

## ⚠️ Integration Status & Known Issues

### Implementation Status

| Component | Gateway | User | Product | Order |
|-----------|:-------:|:----:|:-------:|:-----:|
| Core Endpoints | ✅ | ✅ | ✅ | ✅ |
| Eureka Registration | ✅ | ✅ | ✅ | ✅ |
| GatewayAuthFilter (reads X-User-*) | — | ⚠️ | ✅ | ❌ |
| SecurityFilterChain | — | ✅ | ✅ | ❌ |
| Redis Cache | ✅ gateway | ❌ | ❌ | ❌ |
| JWT Token Gen/Validation | ✅ | ✅ | — | — |
| Feign Clients | — | — | — | ✅ |
| Kafka Producer | — | — | — | ✅ |
| Circuit Breaker | — | — | — | ❌ |

### 🔴 Critical — Must Fix

| # | Issue | Service | Details |
|---|-------|---------|---------|
| 1 | Missing `SecurityConfig` + `GatewayAuthFilter` | Order | No security — defaults to Basic Auth |
| 2 | In-memory token blacklist | User | Won't work with multiple instances — needs Redis |
| 3 | JWT secret mismatch | Product, Order | Different `jwt.secret` than Gateway/User |
| 4 | JWT re-parsing in profile endpoints | User | Should read `X-Username` header instead |

### 🟡 Important — Should Fix

| # | Issue | Service |
|---|-------|---------|
| 5 | No Redis caching (`@Cacheable`) | Product, Order |
| 6 | No Circuit Breaker for Feign calls | Order |
| 7 | Feign header propagation (X-User-* not forwarded) | Order |
| 8 | `UserResponse` field mismatch (`name` vs `username`) | Order ↔ User |
| 9 | Duplicate create endpoint (`/products` + `/products/addproduct`) | Product |

### 🟢 Nice to Have

| # | Enhancement | Priority |
|---|-------------|----------|
| 10 | Kafka consumer service (notifications, inventory) | Medium |
| 11 | Distributed tracing (Zipkin / Micrometer) | Medium |
| 12 | Centralized config (Spring Cloud Config Server) | Medium |
| 13 | API docs (SpringDoc/Swagger per service) | Low |
| 14 | Global exception handler in each service | Low |

> 📖 See [`api-gateway/ARCHITECTURE.md`](api-gateway/ARCHITECTURE.md) for detailed fix instructions with full code examples.

---

## 🧪 Postman Testing Guide

### Step 1: Register

```http
POST http://localhost:8080/api/auth/register
Content-Type: application/json

{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "John@123",
  "role": "ADMIN"
}
```

### Step 2: Login (save the token)

```http
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "username": "john_doe",
  "password": "John@123"
}

→ Response: { "token": "eyJhbGciOiJIUzI1NiJ9..." }
```

### Step 3: Use Token for Protected Endpoints

Add header to all authenticated requests:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

```http
# Create a product (ADMIN only)
POST http://localhost:8080/api/products
Authorization: Bearer <token>
{"name":"Gaming Laptop","description":"High performance","price":1299.99,"stock":50,"categoryId":1}

# Browse products (no auth needed)
GET http://localhost:8080/api/products?page=0&size=10

# Search products
GET http://localhost:8080/api/products/search?query=laptop

# Place an order
POST http://localhost:8080/api/orders/newOrder
Authorization: Bearer <token>
{"userId":1,"items":[{"productId":1,"quantity":2}]}

# View profile
GET http://localhost:8080/api/auth/user/profile
Authorization: Bearer <token>

# Logout (invalidates token)
POST http://localhost:8080/api/auth/logout
Authorization: Bearer <token>
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Gateway** | Spring Cloud Gateway, Netty |
| **Discovery** | Netflix Eureka Server/Client |
| **Auth** | JWT (JJWT 0.12.6), Spring Security |
| **REST Clients** | OpenFeign (inter-service) |
| **Databases** | MySQL 8 (JPA/Hibernate), MongoDB 6 (Spring Data) |
| **Cache / Rate Limit** | Redis 7 (Lettuce, Reactive, Lua scripts) |
| **Messaging** | Apache Kafka |
| **Build** | Maven, Java 21 |
| **Spring Boot** | 3.2.5 |
| **Spring Cloud** | 2023.0.1 |

---

## 📄 License

This project is for learning and demonstration purposes.
