# Product Service - Microservice Documentation

## 📋 Overview

**Product Service** is a Spring Boot microservice responsible for managing products and categories in the Online Shopping platform. It is part of a microservices architecture and communicates with other services through an **API Gateway**.

| Property | Value |
|----------|-------|
| **Service Name** | `PRODUCT-SERVICE` |
| **Port** | `8082` |
| **Base Path** | `/products` |
| **Gateway Route** | `/api/products/**` → `lb://PRODUCT-SERVICE/products/**` |
| **Database** | MySQL (`product_db`) |
| **Cache** | Redis (`localhost:6379`) |
| **Registry** | Eureka Server (`http://localhost:8761/eureka`) |

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
│  │  Redis Cache ←→ ProductService ←→ MySQL Database           │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                          │                    │
                          ▼                    ▼
                 ┌─────────────┐      ┌─────────────────┐
                 │    Redis    │      │  MySQL Database │
                 │   (Cache)   │      │   product_db    │
                 └─────────────┘      └─────────────────┘
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

## 📦 Redis Caching

### Cache Strategy

| Cache Name | Key Pattern | TTL | Description |
|------------|-------------|-----|-------------|
| `products` | `{productId}` | 5 min | Individual product by ID |
| `productPages` | `{page}-{size}` | 3 min | Paginated product lists |
| `productSearch` | `{query}-{page}` | 2 min | Search results |
| `categories` | `'all'` | 30 min | All categories |

### Cache Eviction

| Operation | Evicts |
|-----------|--------|
| Create Product | `products`, `productPages`, `productSearch` |
| Update Product | `products[id]`, `productPages`, `productSearch` |
| Delete Product | `products[id]`, `productPages`, `productSearch` |

### Cache Annotations Used

```java
@Cacheable(value = "products", key = "#id")           // Read from cache
@CacheEvict(value = "products", key = "#id")          // Remove from cache
@Caching(evict = {...})                                // Multiple evictions
```

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

**Error Response** (`404 Not Found`):
```json
{
  "success": false,
  "message": "Product not found with id: 999",
  "data": null
}
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

**Validation Rules**:
| Field | Rule |
|-------|------|
| `name` | Required, not blank |
| `description` | Required, not blank |
| `price` | Required, must be > 0 |
| `stock` | Optional, must be >= 0 |
| `categoryId` | Required, must exist in database |

**Response** (`201 Created`):
```json
{
  "success": true,
  "message": "Product created successfully",
  "data": "Wireless Mouse"
}
```

**Error Response** (`400 Bad Request`):
```json
{
  "timestamp": "2026-04-23T10:30:00",
  "status": 400,
  "error": "Validation Error",
  "message": "Product name is required"
}
```

---

### 5. Update Product

```http
PUT /products/{id}
Content-Type: application/json
Authorization: Bearer <JWT_TOKEN>
```

**Authorization**: `ROLE_ADMIN` required

**Response** (`204 No Content`)

---

### 6. Delete Product

```http
DELETE /products/{id}
Authorization: Bearer <JWT_TOKEN>
```

**Authorization**: `ROLE_ADMIN` required

**Response** (`204 No Content`)

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

---

## 📤 DTOs (Data Transfer Objects)

### CreateProduct (Request)

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
  "data": "T"
}
```

### PagedResponse<T>

```json
{
  "content": ["T"],
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

# JWT Secret (must match API Gateway and other services)
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==

# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms

# Cache Configuration
spring.cache.type=redis
spring.cache.redis.time-to-live=300000

# Actuator endpoints
management.endpoints.web.exposure.include=health,info
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
| `spring-boot-starter-data-redis` | 3.2.5 | Redis Cache |
| `spring-cloud-starter-netflix-eureka-client` | 2023.0.1 | Service Discovery |
| `spring-cloud-starter-openfeign` | 2023.0.1 | Inter-service Communication |
| `mysql-connector-j` | Runtime | MySQL Driver |
| `lombok` | Compile | Boilerplate Reduction |

### Spring Cloud Version

```xml
<spring-cloud.version>2023.0.1</spring-cloud.version>
```

---

## 🔄 Service Integration

### For Order Service - Using OpenFeign

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

### Enable Feign in Your Service

```java
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class YourServiceApplication { }
```

---

## 🚀 Running the Service

### Prerequisites

1. **MySQL** running on port `3306`
2. **Redis** running on port `6379`
3. **Eureka Server** running on port `8761`
4. **Database** `product_db` created

### Docker Compose for Infrastructure

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    container_name: mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    container_name: redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

volumes:
  mysql_data:
  redis_data:
```

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

## 📁 Project Structure

```
product-service/
├── pom.xml
├── README.md
└── src/
    └── main/
        ├── java/com/onlineshopping/product_service/
        │   ├── ProductServiceApplication.java      # @EnableFeignClients
        │   ├── config/
        │   │   └── RedisConfig.java                # Cache configuration
        │   ├── controller/
        │   │   └── ProductController.java          # REST endpoints
        │   ├── dto/
        │   │   ├── ApiResponse.java
        │   │   ├── CreateProduct.java              # With validation
        │   │   ├── PagedResponse.java
        │   │   └── ProductResponse.java
        │   ├── entity/
        │   │   ├── Category.java
        │   │   └── Product.java
        │   ├── exception/
        │   │   ├── CategoryNotFoundException.java
        │   │   ├── GlobalExceptionHandler.java
        │   │   └── ProductNotFoundException.java
        │   ├── repo/
        │   │   ├── CategoryRepository.java
        │   │   └── ProductRepository.java
        │   ├── security/
        │   │   ├── GatewayAuthFilter.java          # Reads Gateway headers
        │   │   ├── SecurityConfig.java             # Security rules
        │   │   ├── JwtFilter.java                  # @Deprecated
        │   │   └── JwtUtil.java                    # @Deprecated
        │   ├── service/
        │   │   └── ProductService.java
        │   └── serviceimpl/
        │       └── ProductServiceImpl.java         # With @Cacheable
        └── resources/
            └── application.properties
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

## 🔧 Troubleshooting

### Common Issues

| Issue | Solution |
|-------|----------|
| `403 Forbidden` on POST/PUT/DELETE | Ensure `X-User-Role: ADMIN` header is sent |
| `Category not found` | Create category first in database |
| `Service not registered in Eureka` | Check Eureka URL in application.properties |
| `Connection refused to MySQL` | Ensure MySQL is running on port 3306 |
| `Connection refused to Redis` | Ensure Redis is running on port 6379 |
| `JWT validation failed` | Ensure jwt.secret matches across all services |

### Logging

```properties
# Add to application.properties for debug logging
logging.level.com.onlineshopping=DEBUG
logging.level.org.springframework.security=DEBUG
logging.level.org.springframework.cache=DEBUG
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
6. **JWT secret must match**: `dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==`
7. **Redis is required** for caching to work

---

## 📄 License

Part of Online Shopping Microservices Platform

---

*Last Updated: April 23, 2026*

