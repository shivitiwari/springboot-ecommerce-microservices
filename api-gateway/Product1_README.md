# Product Service - Microservice Documentation

> **Last Updated:** May 7, 2026

## 📋 Overview

| Property | Value |
|----------|-------|
| **Service Name** | `PRODUCT-SERVICE` |
| **Port** | `8082` |
| **Base Path** | `/products` |
| **Gateway Route** | `/api/products/**` → `lb://PRODUCT-SERVICE` |
| **Database** | MySQL (`product_db`) |
| **Registry** | Eureka Server (`http://localhost:8761/eureka`) |

---

## ✅ Verified Endpoint Mapping (Actual Controller)

`ProductController` is mapped at `@RequestMapping("/products")`:

| HTTP | Controller Path | Client Calls (via Gateway) | Forwarded To Service |
|------|----------------|---------------------------|----------------------|
| GET  | `/products`           | `GET /api/products`              | `GET /products` |
| GET  | `/products/{id}`      | `GET /api/products/{id}`         | `GET /products/{id}` |
| GET  | `/products/search`    | `GET /api/products/search`       | `GET /products/search` |
| POST | `/products`           | `POST /api/products`             | `POST /products` |
| POST | `/products/addproduct`| `POST /api/products/addproduct`  | `POST /products/addproduct` |
| PUT  | `/products/{id}`      | `PUT /api/products/{id}`         | `PUT /products/{id}` |
| DELETE | `/products/{id}`    | `DELETE /api/products/{id}`      | `DELETE /products/{id}` |

---

## ⚠️ REQUIRED CHANGES - Integration Checklist

### 🔴 CRITICAL - Must Fix

#### 1. Remove Duplicate Create Endpoint

**Issue:** Two endpoints do the exact same thing:
```java
POST /products           → createProduct()    // returns ApiResponse<String>
POST /products/addproduct → saveProduct()     // returns ResponseEntity<String>
```

Both call `productService.createProduct(product)`. Having two endpoints for the same operation causes confusion and maintenance issues.

**Fix — Remove `saveProduct()` from `ProductController.java`:**
```java
// DELETE this entire method:
@PostMapping("/addproduct")
public ResponseEntity<String> saveProduct(@RequestBody @Valid CreateProduct product) {
    productService.createProduct(product);
    return ResponseEntity.ok(product.getName() + " added successfully");
}
```

Keep only:
```java
@PostMapping
public ResponseEntity<ApiResponse<String>> createProduct(@RequestBody @Valid CreateProduct product) {
    productService.createProduct(product);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.<String>builder()
                    .success(true)
                    .message("Product created successfully")
                    .data(product.getName())
                    .build());
}
```

---

#### 2. ~~Add Role-Based Access Control~~ — ✅ ALREADY IMPLEMENTED (URL-based)

**SecurityConfig.java already correctly configured:**
```java
.requestMatchers(HttpMethod.POST,   "/products/**").hasRole("ADMIN")   ✅
.requestMatchers(HttpMethod.PUT,    "/products/**").hasRole("ADMIN")   ✅
.requestMatchers(HttpMethod.DELETE, "/products/**").hasRole("ADMIN")   ✅
.requestMatchers(HttpMethod.GET,    "/products/**").permitAll()         ✅
```

> Do NOT add `@PreAuthorize` — URL-based security in `SecurityConfig` already covers this.
> `@PreAuthorize` and URL-based security are two different approaches. You only need one.

**GatewayAuthFilter.java is also already correctly implemented:**
- Reads `X-User-Id`, `X-Username`, `X-User-Role` headers from Gateway ✅
- Adds `ROLE_` prefix if not present ✅
- Sets `SecurityContext` with proper authorities ✅
- Stores `GatewayUserDetails` record in auth details ✅

---

#### 3. API Gateway Route — Fixed (GET is now public)

**Issue found:** Product Service `SecurityConfig` has `GET /products/**` as `permitAll()`,
but the Gateway was requiring JWT for ALL methods including GET.
This meant guests could never browse products — Gateway blocked them with 401 before
the request ever reached Product Service.

**Fix applied in `FilterConfig.java` (api-gateway):**
```java
// Route 4a: GET — PUBLIC (no JWT) — guests can browse products
.route("product-service-public", r -> r
        .path("/api/products/**", "/api/products")
        .and().method("GET")
        .filters(f -> f.rewritePath("/api/products/?(?<segment>.*)", "/products/${segment}"))
        .uri("lb://PRODUCT-SERVICE"))

// Route 4b: POST/PUT/DELETE — JWT REQUIRED — admin operations
.route("product-service-protected", r -> r
        .path("/api/products/**", "/api/products")
        .and().method("POST", "PUT", "DELETE")
        .filters(f -> f
                .filter(jwtAuthFilter)   // validates JWT, forwards X-User-Role
                .rewritePath("/api/products/?(?<segment>.*)", "/products/${segment}"))
        .uri("lb://PRODUCT-SERVICE"))
```

**Full security flow for write operations:**
```
Client POST /api/products  (with JWT Bearer token)
    │
    ▼
API Gateway JwtAuthFilter
    → validates JWT signature
    → checks Redis blacklist
    → extracts role="ADMIN"
    → forwards X-User-Role: ADMIN
    │
    ▼
RewritePath → /products
    │
    ▼
Product Service GatewayAuthFilter
    → reads X-User-Role: ADMIN
    → sets ROLE_ADMIN in SecurityContext
    │
    ▼
Product Service SecurityConfig
    → POST /products/** → hasRole("ADMIN") → ✅ ALLOWED

Client POST /api/products  (with JWT, but role="USER")
    → Gateway forwards X-User-Role: USER
    → SecurityConfig: hasRole("ADMIN") → ✅ 403 FORBIDDEN (correct!)
```

---

### ✅ Already Implemented (No Changes Needed)

#### Request Validation — ✅ Already present
`@Valid` is on all `@RequestBody` controller methods. ✅

#### GatewayAuthFilter — ✅ Fully implemented
Reads gateway headers, sets SecurityContext, stores `GatewayUserDetails`. ✅

#### SecurityConfig — ✅ Correctly configured
URL-based role security, stateless session, CSRF disabled. ✅

---

### 🟡 IMPORTANT - Should Implement

#### 4. Fix JWT Secret (Mismatch with API Gateway)

**Update:** `src/main/resources/application.properties`
```properties
# Must match API Gateway and all other services:
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

---

#### 5. Remove Duplicate Create Endpoint

**Issue:** Two endpoints do the exact same thing:
```java
POST /products            → createProduct()     ← keep this
POST /products/addproduct → saveProduct()        ← DELETE this
```

---

### 🟢 NICE TO HAVE

#### 6. Add Redis Caching


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

# Cache Configuration
spring.cache.type=redis
spring.cache.redis.time-to-live=300000
```

**Create File:** `src/main/java/com/onlineshopping/product_service/config/RedisConfig.java`

```java
package com.onlineshopping.product_service.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .disableCachingNullValues();

        // Custom TTLs per cache
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("products", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigs.put("productPages", defaultConfig.entryTtl(Duration.ofMinutes(3)));
        cacheConfigs.put("categories", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigs.put("productSearch", defaultConfig.entryTtl(Duration.ofMinutes(2)));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
```

**Update Service Implementation:** `ProductServiceImpl.java`

```java
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

@Service
public class ProductServiceImpl implements ProductService {

    @Cacheable(value = "products", key = "#id")
    public ProductResponse getProductById(Long id) {
        // ... existing code
    }

    @Cacheable(value = "productPages", key = "#pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        // ... existing code
    }

    @Cacheable(value = "productSearch", key = "#query + '-' + #pageable.pageNumber")
    public Page<ProductResponse> searchProducts(String query, Pageable pageable) {
        // ... existing code
    }

    @Cacheable(value = "categories", key = "'all'")
    public List<Category> getAllCategories() {
        // ... existing code
    }

    @Caching(evict = {
        @CacheEvict(value = "products", allEntries = true),
        @CacheEvict(value = "productPages", allEntries = true),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
    public ProductResponse createProduct(CreateProduct dto) {
        // ... existing code
    }

    @Caching(evict = {
        @CacheEvict(value = "products", key = "#id"),
        @CacheEvict(value = "productPages", allEntries = true),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
    public ProductResponse updateProduct(Long id, CreateProduct dto) {
        // ... existing code
    }

    @Caching(evict = {
        @CacheEvict(value = "products", key = "#id"),
        @CacheEvict(value = "productPages", allEntries = true),
        @CacheEvict(value = "productSearch", allEntries = true)
    })
    public void deleteProduct(Long id) {
        // ... existing code
    }
}
```

#### 4. Add Global Exception Handler

**Create File:** `src/main/java/com/onlineshopping/product_service/exception/GlobalExceptionHandler.java`

```java
package com.onlineshopping.product_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 403,
            "error", "Forbidden",
            "message", "Access denied. ADMIN role required."
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 400,
            "error", "Validation Error",
            "message", e.getBindingResult().getAllErrors().get(0).getDefaultMessage()
        ));
    }
}
```

#### 5. Add Feign Client for Order Service (Optional Enhancement)

**Issue:** Product Service could call Order Service to show "recently ordered" status on products.

**Create File:** `src/main/java/com/onlineshopping/product_service/client/OrderClient.java`

```java
package com.onlineshopping.product_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);
}
```

**Add Dependency:** `pom.xml`
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

**Enable Feign:** Add `@EnableFeignClients` to main application class.

---

### 🟢 NICE TO HAVE - Optional Improvements

#### 6. Remove Deprecated JWT Classes

**Issue:** `JwtFilter.java` and `JwtUtil.java` exist but are deprecated. JWT validation is done at API Gateway.

**Action:** 
- Delete or mark as `@Deprecated`: `src/main/java/com/onlineshopping/product_service/security/JwtFilter.java`
- Delete or mark as `@Deprecated`: `src/main/java/com/onlineshopping/product_service/security/JwtUtil.java`

#### 7. Add Request Validation

**Issue:** No `@Valid` annotation on request body in controller.

**Update Controller:**
```java
@PostMapping
public ResponseEntity<?> createProduct(@Valid @RequestBody CreateProduct dto) {
    // ...
}
```

**Update DTO with validation annotations:**
```java
public class CreateProduct {
    @NotBlank(message = "Product name is required")
    private String name;
    
    @NotBlank(message = "Description is required")
    private String description;
    
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.01", message = "Price must be greater than 0")
    private BigDecimal price;
    
    @Min(value = 0, message = "Stock cannot be negative")
    private int stock;
    
    @NotNull(message = "Category ID is required")
    private Long categoryId;
}
```

---

### 📋 Updated Project Structure (After Changes)

```
product-service/
├── src/main/java/com/onlineshopping/product_service/
│   ├── ProductServiceApplication.java      # ADD @EnableFeignClients (if adding OrderClient)
│   ├── client/                              # NEW (optional)
│   │   └── OrderClient.java                 # NEW - Feign client for Order Service
│   ├── config/
│   │   └── RedisConfig.java                 # NEW - Cache configuration
│   ├── controller/
│   │   └── ProductController.java           # MODIFY - Add @Valid
│   ├── dto/
│   │   └── CreateProduct.java               # MODIFY - Add validation annotations
│   ├── exception/                           # NEW
│   │   └── GlobalExceptionHandler.java      # NEW
│   ├── security/
│   │   ├── GatewayAuthFilter.java           # EXISTS ✅
│   │   ├── SecurityConfig.java              # EXISTS ✅
│   │   ├── JwtFilter.java                   # DEPRECATED - Remove
│   │   └── JwtUtil.java                     # DEPRECATED - Remove
│   └── service/
│       └── ProductServiceImpl.java          # MODIFY - Add @Cacheable annotations
└── src/main/resources/
    └── application.properties               # MODIFY - JWT secret + Redis config
```

---

### 📊 Cache Strategy Summary

| Cache Name | Key Pattern | TTL | Eviction Trigger |
|------------|-------------|-----|------------------|
| `products` | `{productId}` | 5 min | Create/Update/Delete product |
| `productPages` | `{page}-{size}` | 3 min | Any product change |
| `productSearch` | `{query}-{page}` | 2 min | Any product change |
| `categories` | `'all'` | 30 min | Category CRUD |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         API GATEWAY (8080)                          │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  JWT Validation → Extract User Info → Add Headers           │   │
│  │  Headers: X-User-Id, X-Username, X-User-Role                │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      PRODUCT SERVICE (8082)                         │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  GatewayAuthFilter → Read Headers → Set SecurityContext     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  ProductController → ProductService → ProductRepository     │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────���───────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                          ┌─────────────────┐
                          │  MySQL Database │
                          │   product_db    │
                          └─────────────────┘
```

---

## 🔐 Security Architecture

### Centralized Authentication (API Gateway)

This service uses **Gateway-Based Security**. JWT validation is NOT performed in this service. Instead:

1. **API Gateway** validates JWT tokens
2. **API Gateway** extracts user information from the token
3. **API Gateway** forwards user info via HTTP headers to downstream services
4. **Product Service** trusts and reads these headers

### Required Headers from API Gateway

| Header | Description | Example |
|--------|-------------|---------|
| `X-User-Id` | User's unique identifier | `123` |
| `X-Username` | Authenticated username | `john_doe` |
| `X-User-Role` | User's role (without ROLE_ prefix) | `ADMIN` or `USER` |

### GatewayAuthFilter Implementation

```java
// Location: com.onlineshopping.product_service.security.GatewayAuthFilter

// Reads these headers:
private static final String HEADER_USER_ID = "X-User-Id";
private static final String HEADER_USERNAME = "X-Username";
private static final String HEADER_USER_ROLE = "X-User-Role";

// Converts role to Spring Security format:
// "ADMIN" → "ROLE_ADMIN"
// "USER" → "ROLE_USER"
```

### API Gateway Configuration Required

**In your API Gateway's JWT Filter**, after validating the token, add these headers:

```java
// Example for Spring Cloud Gateway (WebFlux)
@Component
public class JwtAuthFilter implements GatewayFilter {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = extractToken(exchange);
        
        if (token != null && jwtUtil.isTokenValid(token)) {
            Claims claims = jwtUtil.extractAllClaims(token);
            String username = claims.getSubject();
            String userId = claims.get("userId", String.class);
            String role = claims.get("role", String.class);
            
            // Add headers for downstream services
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId != null ? userId : "")
                .header("X-Username", username)
                .header("X-User-Role", role != null ? role : "USER")
                .build();
            
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }
        
        return chain.filter(exchange);
    }
}
```

### Security Rules

| HTTP Method | Endpoint | Required Role | Description |
|-------------|----------|---------------|-------------|
| `GET` | `/products/**` | **Public** | Anyone can view products |
| `POST` | `/products/**` | `ROLE_ADMIN` | Only admins can create |
| `PUT` | `/products/**` | `ROLE_ADMIN` | Only admins can update |
| `DELETE` | `/products/**` | `ROLE_ADMIN` | Only admins can delete |

---

## 📡 API Endpoints

### Base URL
- **Direct**: `http://localhost:8082/products`
- **Via Gateway**: `http://localhost:8080/api/products`

---

### 1. Get All Products (Paginated)

```http
GET /products?page=0&size=10&sort=name,asc
```

**Authorization**: Public (No token required)

**Query Parameters**:
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number (0-indexed) |
| `size` | int | 20 | Items per page |
| `sort` | string | - | Sort field and direction |

**Response** (`200 OK`):
```json
{
  "success": true,
  "message": "Products fetched successfully",
  "data": {
    "content": [
      {
        "productId": 1,
        "name": "Laptop",
        "description": "High-performance laptop",
        "price": 999.99,
        "stock": 50,
        "categoryId": 1,
        "categoryName": "Electronics"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 100,
    "totalPages": 10
  }
}
```

---

### 2. Get Product by ID

```http
GET /products/{id}
```

**Authorization**: Public

**Path Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | Long | Product ID |

**Response** (`200 OK`):
```json
{
  "productId": 1,
  "name": "Laptop",
  "description": "High-performance laptop",
  "price": 999.99,
  "stock": 50,
  "categoryId": 1,
  "categoryName": "Electronics"
}
```

**Error Response** (`500 Internal Server Error`):
```
Product not found with id: {id}
```

---

### 3. Search Products

```http
GET /products/search?query=laptop&page=0&size=10
```

**Authorization**: Public

**Query Parameters**:
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | Yes | Search term (matches name OR description) |
| `page` | int | No | Page number |
| `size` | int | No | Items per page |

**Response** (`200 OK`):
```json
{
  "success": true,
  "message": "Products search results",
  "data": {
    "content": [...],
    "page": 0,
    "size": 10,
    "totalElements": 5,
    "totalPages": 1
  }
}
```

---

### 4. Create Product

```http
POST /products
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Authorization**: `ROLE_ADMIN` required

**Headers Required from Gateway**:
```
X-Username: admin_user
X-User-Role: ADMIN
```

**Request Body**:
```json
{
  "name": "Wireless Mouse",
  "description": "Ergonomic wireless mouse with USB receiver",
  "price": 29.99,
  "stock": 100,
  "categoryId": 1
}
```

**Response** (`201 Created`):
```json
{
  "success": true,
  "message": "Product created successfully",
  "data": "Wireless Mouse"
}
```

**Error Response** (`403 Forbidden`):
```
Access Denied - User does not have ROLE_ADMIN
```

---

### 5. Update Product

```http
PUT /products/{id}
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Authorization**: `ROLE_ADMIN` required

**Path Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | Long | Product ID to update |

**Request Body**:
```json
{
  "name": "Wireless Mouse Pro",
  "description": "Updated ergonomic wireless mouse",
  "price": 39.99,
  "stock": 150,
  "categoryId": 1
}
```

**Response** (`204 No Content`)

---

### 6. Delete Product

```http
DELETE /products/{id}
Authorization: Bearer <JWT_TOKEN>
```

**Authorization**: `ROLE_ADMIN` required

**Path Parameters**:
| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | Long | Product ID to delete |

**Response** (`204 No Content`)

---

### 7. Add Product (Alternative Endpoint)

```http
POST /products/addproduct
Content-Type: application/json
```

**Authorization**: `ROLE_ADMIN` required

**Request Body**: Same as Create Product

**Response** (`200 OK`):
```
Wireless Mouse added successfully
```

---

## 📦 Data Models

### Product Entity

```java
@Entity
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    @NonNull
    private String name;
    
    @Lob
    @Column(name = "description", nullable = false, length = 50)
    private String description;
    
    @NonNull
    private BigDecimal price;
    
    private int stock;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}
```

### Category Entity

```java
@Entity
@Table(name = "category")
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
}
```

### Database Tables

```sql
-- Categories Table
CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

-- Products Table
CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    price DECIMAL(19,2) NOT NULL,
    stock INT DEFAULT 0,
    category_id BIGINT NOT NULL,
    FOREIGN KEY (category_id) REFERENCES category(id)
);
```

---

## 📤 DTOs (Data Transfer Objects)

### CreateProduct (Request)

```json
{
  "name": "string",           // Required
  "description": "string",    // Required
  "price": 0.00,              // Required (BigDecimal)
  "stock": 0,                 // Optional (int)
  "categoryId": 1             // Required (Long) - Must exist in category table
}
```

### ProductResponse

```json
{
  "productId": 1,
  "name": "string",
  "description": "string",
  "price": 0.00,
  "stock": 0,
  "categoryId": 1,
  "categoryName": "string"
}
```

### ApiResponse<T>

```json
{
  "success": true,
  "message": "string",
  "data": T
}
```

### PagedResponse<T>

```json
{
  "content": [T],
  "page": 0,
  "size": 10,
  "totalElements": 100,
  "totalPages": 10
}
```

---

## ⚙️ Configuration

### application.properties

```properties
# Application Identity
spring.application.name=PRODUCT-SERVICE
server.port=8082

# Database Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/product_db
spring.datasource.username=root
spring.datasource.password=your_password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA Hibernate
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.jpa.properties.hibernate.format_sql=true

# Eureka Client Configuration
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.instance.prefer-ip-address=true

# JWT Secret (kept for backward compatibility)
jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZA==

# Actuator endpoints
management.endpoints.web.exposure.include=health,info
```

### Required Environment Variables (Optional)

```bash
# Override via environment variables
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/product_db
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=your_password
EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://localhost:8761/eureka
```

---

## 🔗 Dependencies (pom.xml)

### Key Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| `spring-boot-starter-web` | 3.2.5 | REST API |
| `spring-boot-starter-data-jpa` | 3.2.5 | Database ORM |
| `spring-boot-starter-security` | 3.2.5 | Security Filter Chain |
| `spring-boot-starter-validation` | 3.2.5 | Request Validation |
| `spring-boot-starter-actuator` | 3.2.5 | Health Checks |
| `spring-cloud-starter-netflix-eureka-client` | 2023.0.1 | Service Discovery |
| `mysql-connector-j` | Runtime | MySQL Driver |
| `lombok` | Compile | Boilerplate Reduction |
| `jjwt-api` | 0.12.6 | JWT (Legacy, not used) |

### Spring Cloud Version

```xml
<spring-cloud.version>2023.0.1</spring-cloud.version>
```

---

## 🔄 Service Integration

### For Order Service

To call Product Service from Order Service:

```java
// Using RestTemplate or WebClient
@Service
public class ProductClient {
    
    private final RestTemplate restTemplate;
    
    public ProductResponse getProduct(Long productId) {
        return restTemplate.getForObject(
            "http://PRODUCT-SERVICE/products/{id}",
            ProductResponse.class,
            productId
        );
    }
    
    public boolean checkStock(Long productId, int quantity) {
        ProductResponse product = getProduct(productId);
        return product.getStock() >= quantity;
    }
}
```

### Using OpenFeign (Recommended)

```java
@FeignClient(name = "PRODUCT-SERVICE")
public interface ProductServiceClient {
    
    @GetMapping("/products/{id}")
    ProductResponse getProductById(@PathVariable("id") Long id);
    
    @GetMapping("/products")
    ApiResponse<PagedResponse<ProductResponse>> getAllProducts(
        @RequestParam("page") int page,
        @RequestParam("size") int size
    );
    
    @GetMapping("/products/search")
    ApiResponse<PagedResponse<ProductResponse>> searchProducts(
        @RequestParam("query") String query,
        @RequestParam("page") int page,
        @RequestParam("size") int size
    );
}
```

---

## 🚀 Running the Service

### Prerequisites

1. **MySQL** running on port `3306`
2. **Eureka Server** running on port `8761`
3. **Database** `product_db` created

### Start Commands

```bash
# Create database
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS product_db;"

# Run with Maven
cd product-service
mvn spring-boot:run

# Or build and run JAR
mvn clean package -DskipTests
java -jar target/product-service-0.0.1-SNAPSHOT.jar
```

### Verify Service

```bash
# Health Check
curl http://localhost:8082/actuator/health

# Check Eureka Registration
# Visit: http://localhost:8761
# Should see: PRODUCT-SERVICE registered
```

---

## 🧪 Testing Examples

### Using cURL

```bash
# Get all products (Public)
curl http://localhost:8082/products

# Search products
curl "http://localhost:8082/products/search?query=laptop"

# Get product by ID
curl http://localhost:8082/products/1

# Create product (Admin - requires headers)
curl -X POST http://localhost:8082/products \
  -H "Content-Type: application/json" \
  -H "X-Username: admin" \
  -H "X-User-Role: ADMIN" \
  -d '{
    "name": "Test Product",
    "description": "Test description",
    "price": 99.99,
    "stock": 10,
    "categoryId": 1
  }'

# Via API Gateway (with JWT)
curl http://localhost:8080/api/products \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## 📁 Project Structure

```
product-service/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/com/onlineshopping/product_service/
        │   ├── ProductServiceApplication.java
        │   ├── client/
        │   │   └── OrderClient.java                 # NEW - Feign client for Order Service
        │   ├── config/
        │   │   └── RedisConfig.java                 # NEW - Cache configuration
        │   ├── controller/
        │   │   └── ProductController.java
        │   ├── dto/
        │   │   ├── ApiResponse.java
        │   │   ├── CreateProduct.java
        │   │   ├── PagedResponse.java
        │   │   └── ProductResponse.java
        │   ├── entity/
        │   │   ├── Category.java
        │   │   └── Product.java
        │   ├── exception/
        │   │   └── GlobalExceptionHandler.java      # NEW - Global exception handler
        │   ├── repo/
        │   │   ├── CategoryRepository.java
        │   │   └── ProductRepository.java
        │   ├── security/
        │   │   ├── GatewayAuthFilter.java    # Reads headers from Gateway
        │   │   ├── SecurityConfig.java        # Security rules
        │   │   ├── JwtFilter.java             # DEPRECATED - Remove
        │   │   └── JwtUtil.java               # DEPRECATED - Remove
        │   ├── service/
        │   │   └── ProductServiceImpl.java
        └── resources/
            └── application.properties
```

---

## 🔧 Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| `403 Forbidden` on POST/PUT/DELETE | Ensure `X-User-Role: ADMIN` header is sent |
| `Category not found` | Create category first in database |
| `Service not registered in Eureka` | Check Eureka URL in application.properties |
| `Connection refused to MySQL` | Ensure MySQL is running on port 3306 |

### Logging

```properties
# Add to application.properties for debug logging
logging.level.com.onlineshopping=DEBUG
logging.level.org.springframework.security=DEBUG
```

---

## 📞 Inter-Service Communication Summary

| From Service | To Product Service | Purpose |
|--------------|-------------------|---------|
| **Order Service** | `GET /products/{id}` | Validate product exists |
| **Order Service** | `GET /products/{id}` | Check stock availability |
| **API Gateway** | All endpoints | Route with user headers |
| **Admin Panel** | `POST/PUT/DELETE` | Manage products |

---

## 📌 Important Notes for Other Microservices

1. **Always go through API Gateway** for authenticated requests
2. **JWT validation is centralized** at API Gateway level
3. **Trust headers** `X-User-Id`, `X-Username`, `X-User-Role` from Gateway
4. **Use Eureka service name** `PRODUCT-SERVICE` for load-balanced calls
5. **Categories must exist** before creating products
6. **Use same JWT secret** across all services if doing local validation

---

## 📄 License

Part of Online Shopping Microservices Platform

---

*Last Updated: April 2026*

---

## ⚠️ MISSING IMPLEMENTATIONS — Must Add

### 🔴 CRITICAL

#### A. Remove Duplicate Endpoint
`POST /products/addproduct` does the same as `POST /products`. Delete `saveProduct()` method from `ProductController.java`.

#### B. Fix JWT Secret
```properties
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

---

### 🟡 IMPORTANT

#### C. Add Redis Caching to ProductServiceImpl
See Section 4 (Add Redis Caching) for full `@Cacheable` / `@CacheEvict` implementation.

#### D. Remove Deprecated JwtFilter.java and JwtUtil.java
JWT validation is at API Gateway. These files are unused and confusing.
```
DELETE: src/main/java/com/onlineshopping/product_service/security/JwtFilter.java
DELETE: src/main/java/com/onlineshopping/product_service/security/JwtUtil.java
```

#### E. Add Global Exception Handler
See Section 4 for `GlobalExceptionHandler.java` implementation.

#### F. Add Category Endpoints (Missing from Controller)
If `CategoryRepository` exists, expose category management endpoints:
```java
@RestController
@RequestMapping("/categories")
public class CategoryController {

    @GetMapping
    public ResponseEntity<List<Category>> getAllCategories() { ... }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")  // or SecurityConfig URL rule
    public ResponseEntity<Category> createCategory(@RequestBody Category category) { ... }
}
```
> Without this, admins cannot create categories required for products.

---

### ✅ Already Correct (No Changes Needed)

| Item | Status |
|------|--------|
| `SecurityConfig.java` with URL-based role security | ✅ |
| `GatewayAuthFilter.java` reading X-User headers | ✅ |
| `@Valid` on all controller `@RequestBody` | ✅ |
| `RewritePath` in Gateway (`/api/products/**` → `/products/**`) | ✅ Fixed |
| GET endpoints public, write endpoints ADMIN-only | ✅ |

---

## 🧪 Postman Testing Guide

> **All requests go through API Gateway at `http://localhost:8080`**
> GET requests are public (no token needed). POST/PUT/DELETE require ADMIN JWT token.

### Prerequisites — Start services in this order:
1. Redis (`localhost:6379`)
2. MySQL (`localhost:3306`)
3. Eureka Server (`localhost:8761`)
4. User Service (`localhost:8081`) — needed for admin login
5. Product Service (`localhost:8082`)
6. API Gateway (`localhost:8080`)

> Get ADMIN token first: `POST http://localhost:8080/api/auth/login` with admin credentials

---

### 1. Get All Products (Paginated) — PUBLIC
```
Method:  GET
URL:     http://localhost:8080/api/products
         http://localhost:8080/api/products?page=0&size=10&sort=name,asc

No Auth required (public browsing)

Expected Response:
{
    "success": true,
    "message": "Products fetched successfully",
    "data": {
        "content": [
            { "id": 1, "name": "Laptop", "price": 999.99, "categoryName": "Electronics" }
        ],
        "page": 0,
        "size": 10,
        "totalElements": 25,
        "totalPages": 3
    }
}
```

### 2. Get Product by ID — PUBLIC
```
Method:  GET
URL:     http://localhost:8080/api/products/1

No Auth required

Expected Response:
{
    "id": 1,
    "name": "Laptop",
    "price": 999.99,
    "categoryName": "Electronics"
}

Error: 404/500 if product not found
```

### 3. Search Products — PUBLIC
```
Method:  GET
URL:     http://localhost:8080/api/products/search?query=laptop
         http://localhost:8080/api/products/search?query=laptop&page=0&size=5

No Auth required

Expected Response:
{
    "success": true,
    "message": "Products search results",
    "data": {
        "content": [...],
        "page": 0,
        "size": 5,
        "totalElements": 3,
        "totalPages": 1
    }
}
```

### 4. Create Product — ADMIN Only
```
Method:  POST
URL:     http://localhost:8080/api/products
Headers:
  Authorization: Bearer <ADMIN_jwt_token>
  Content-Type: application/json

Body (raw JSON):
{
    "name": "Laptop",
    "description": "High performance laptop",
    "price": 999.99,
    "stock": 50,
    "categoryId": 1
}

Expected: 201 Created
{
    "success": true,
    "message": "Product created successfully",
    "data": "Laptop"
}

Error: 403 Forbidden if token has role USER (not ADMIN)
Error: 401 Unauthorized if no token
Error: 400 Bad Request if validation fails
Rate Limit: 150 requests/min (Gateway)
```

### 5. Update Product — ADMIN Only
```
Method:  PUT
URL:     http://localhost:8080/api/products/1
Headers:
  Authorization: Bearer <ADMIN_jwt_token>
  Content-Type: application/json

Body (raw JSON):
{
    "name": "Laptop Pro",
    "description": "Updated description",
    "price": 1199.99,
    "stock": 30,
    "categoryId": 1
}

Expected: 204 No Content (empty body)
Effect:   @CacheEvict removes product from Redis cache
```

### 6. Delete Product — ADMIN Only
```
Method:  DELETE
URL:     http://localhost:8080/api/products/1
Headers: Authorization: Bearer <ADMIN_jwt_token>

Expected: 204 No Content
Effect:   @CacheEvict removes product from Redis cache
```

---

### ❌ Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` on POST/PUT/DELETE | No JWT token | Add Authorization Bearer header |
| `403 Forbidden` on POST/PUT/DELETE | Token has role USER not ADMIN | Login with ADMIN account |
| `400 Bad Request` on create | Validation failed (name empty, price ≤ 0, etc.) | Check request body fields |
| `404/500` on GET by ID | Product doesn't exist | Check product ID |
| `Category not found` on create | Category ID doesn't exist in DB | Create category first |
| `Service not found` in Eureka | Product service not started | Start product-service |


