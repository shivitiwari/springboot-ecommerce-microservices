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
│  │  ProductController → ProductService → ProductRepository     │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
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
        │   ├── repo/
        │   │   ├── CategoryRepository.java
        │   │   └── ProductRepository.java
        │   ├── security/
        │   │   ├── GatewayAuthFilter.java    # Reads headers from Gateway
        │   │   ├── SecurityConfig.java        # Security rules
        │   │   ├── JwtFilter.java             # @Deprecated
        │   │   └── JwtUtil.java               # @Deprecated
        │   ├── service/
        │   │   └── ProductService.java
        │   └── serviceimpl/
        │       └── ProductServiceImpl.java
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

