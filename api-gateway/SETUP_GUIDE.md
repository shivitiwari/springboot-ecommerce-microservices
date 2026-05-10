# Online Shopping Microservices - Complete Setup Guide

## 📁 Complete Project Structure

```
Online Shopping/
├── eureka-server/          (Port: 8761) - Service Registry [NEW - CREATE THIS]
├── api-gateway/            (Port: 8080) - API Gateway ✅ [FIXED]
├── user-service/           (Port: 8081) - Authentication & Users [CREATE THIS]
├── product-service/        (Port: 8082) - Product Management [CREATE THIS]
├── order-service/          (Port: 8083) - Order Management [CREATE THIS]
└── docker-compose.yml      (Redis + MySQL) [CREATE THIS]
```

---

## 🔧 1. Eureka Server Setup (CREATE NEW PROJECT)

### 1.1 Create Project Structure
```
eureka-server/
├── pom.xml
└── src/main/
    ├── java/com/onlineshopping/eureka/
    │   └── EurekaServerApplication.java
    └── resources/
        └── application.properties
```

### 1.2 pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.onlineshopping</groupId>
    <artifactId>eureka-server</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>eureka-server</name>
    <description>Eureka Discovery Server for Online Shopping</description>

    <properties>
        <java.version>21</java.version>
        <spring-cloud.version>2023.0.1</spring-cloud.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 1.3 EurekaServerApplication.java
```java
package com.onlineshopping.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
```

### 1.4 application.properties (Eureka Server)
```properties
spring.application.name=eureka-server
server.port=8761

# Standalone mode - don't register with itself
eureka.client.register-with-eureka=false
eureka.client.fetch-registry=false

# Dashboard
eureka.server.enable-self-preservation=false
```

---

## 🔧 2. User Service Template (Port 8081)

### 2.1 Required Dependencies (pom.xml additions)
```xml
<dependencies>
    <!-- Spring Web -->
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
    
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    
    <!-- Eureka Client -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    
    <!-- JWT -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.6</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.6</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 2.2 application.properties (User Service)
```properties
spring.application.name=USER-SERVICE
server.port=8081

# Eureka
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.instance.prefer-ip-address=true

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/user_db
spring.datasource.username=root
spring.datasource.password=root
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# JWT
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

### 2.3 Required API Endpoints
```
POST /api/auth/register    - Register new user
POST /api/auth/login       - Login and get JWT token
POST /api/auth/logout      - Logout (blacklist token)
GET  /api/users/profile    - Get current user profile
PUT  /api/users/profile    - Update user profile
```

---

## 🔧 3. Product Service Template (Port 8082)

### 3.1 application.properties
```properties
spring.application.name=PRODUCT-SERVICE
server.port=8082

# Eureka
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.instance.prefer-ip-address=true

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/product_db
spring.datasource.username=root
spring.datasource.password=root
spring.jpa.hibernate.ddl-auto=update
```

### 3.2 Required API Endpoints
```
GET    /api/products           - List all products (cached)
GET    /api/products/{id}      - Get product by ID (cached)
POST   /api/products           - Create product (admin)
PUT    /api/products/{id}      - Update product (admin)
DELETE /api/products/{id}      - Delete product (admin)
GET    /api/products/search    - Search products
```

---

## 🔧 4. Order Service Template (Port 8083)

### 4.1 application.properties
```properties
spring.application.name=ORDER-SERVICE
server.port=8083

# Eureka
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.instance.prefer-ip-address=true

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/order_db
spring.datasource.username=root
spring.datasource.password=root
spring.jpa.hibernate.ddl-auto=update
```

### 4.2 Required API Endpoints
```
POST   /api/orders              - Create order
GET    /api/orders              - Get user's orders
GET    /api/orders/{id}         - Get order by ID
PUT    /api/orders/{id}/status  - Update order status
DELETE /api/orders/{id}         - Cancel order
```

---

## 🐳 5. Docker Compose (Infrastructure)

### docker-compose.yml
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
      - ./init-db.sql:/docker-entrypoint-initdb.d/init.sql

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

### init-db.sql (Create databases)
```sql
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS product_db;
CREATE DATABASE IF NOT EXISTS order_db;
```

---

## 🚀 Startup Order

1. **Start Infrastructure:**
   ```bash
   docker-compose up -d
   ```

2. **Start Eureka Server (First!):**
   ```bash
   cd eureka-server
   mvn spring-boot:run
   ```

3. **Start API Gateway:**
   ```bash
   cd api-gateway
   mvn spring-boot:run
   ```

4. **Start Microservices (Any Order):**
   ```bash
   cd user-service && mvn spring-boot:run
   cd product-service && mvn spring-boot:run
   cd order-service && mvn spring-boot:run
   ```

5. **Verify:**
   - Eureka Dashboard: http://localhost:8761
   - API Gateway Health: http://localhost:8080/actuator/health
   - All services should be registered in Eureka

---

## 🧪 Test Flow

```bash
# 1. Register User
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "test", "email": "test@example.com", "password": "password123"}'

# 2. Login (Get JWT Token)
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "test", "password": "password123"}'

# 3. Access Protected Endpoint
curl http://localhost:8080/api/products \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## ✅ What's Now Implemented in API Gateway

| Component | Status | Description |
|-----------|--------|-------------|
| JWT Auth Filter | ✅ | Validates tokens, extracts claims |
| Redis Cache Filter | ✅ | Caches GET responses |
| Rate Limiting Filter | ✅ | 100 req/min per IP |
| Token Blacklist Service | ✅ | Logout support via Redis |
| Eureka Discovery | ✅ | Load balancing with lb:// |
| Route Configuration | ✅ | All 4 routes configured |
| CORS Configuration | ✅ | Global CORS enabled |
| Actuator Endpoints | ✅ | Health, Info, Gateway |

---

## 🔮 Optional Enhancements

1. **Circuit Breaker (Resilience4j)** - Handle downstream failures
2. **Distributed Tracing (Zipkin)** - Request tracing across services
3. **Config Server** - Centralized configuration
4. **Kafka/RabbitMQ** - Event-driven communication
5. **API Documentation (OpenAPI/Swagger)** - Auto-generated docs

