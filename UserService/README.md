# UserService - Microservice Documentation

> **Version:** 0.0.1-SNAPSHOT  
> **Port:** 8081  
> **Base URL:** `http://localhost:8081`  
> **Service Name (Eureka):** UserService  
> **Last Updated:** April 29, 2026

This document provides comprehensive documentation for the **UserService** microservice in the Online Shopping platform. It is designed to help other microservices understand how to integrate with this service.

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [Configuration](#configuration)
4. [Security](#security)
5. [API Endpoints](#api-endpoints)
6. [DTOs (Data Transfer Objects)](#dtos-data-transfer-objects)
7. [Service Layer Methods](#service-layer-methods)
8. [Token Blacklist Service](#token-blacklist-service)
9. [JWT Utility Methods](#jwt-utility-methods)
10. [Integration Guide for Other Microservices](#integration-guide-for-other-microservices)
11. [Error Handling](#error-handling)
12. [Database Schema](#database-schema)
13. [Redis Configuration](#redis-configuration)
14. [Feign Client Setup](#feign-client-setup)

---

## Overview

UserService handles:
- **User Registration** - Create new user accounts
- **User Authentication** - Login with JWT token generation (includes userId, role, email claims)
- **User Logout** - Token blacklisting via Redis for secure logout across all instances
- **User Profile Management** - Get and update user profiles
- **JWT Token Validation** - Validate tokens for other microservices
- **Redis Caching** - Cached user lookups for improved performance

---

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Framework | Spring Boot | 3.2.5 |
| Java | JDK | 21 |
| Database | MySQL | Latest |
| Security | Spring Security + JWT | jjwt 0.12.6 |
| Service Discovery | Eureka Client | 2023.0.1 |
| Cache & Token Blacklist | Redis | Latest |
| Inter-service Communication | OpenFeign | Spring Cloud |
| Build Tool | Maven | - |

### Dependencies (pom.xml)
```xml
<!-- Core Dependencies -->
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-security
spring-boot-starter-validation
spring-boot-starter-actuator

<!-- JWT -->
io.jsonwebtoken:jjwt-api:0.12.6
io.jsonwebtoken:jjwt-impl:0.12.6
io.jsonwebtoken:jjwt-jackson:0.12.6

<!-- Database -->
mysql-connector-j

<!-- Service Discovery -->
spring-cloud-starter-netflix-eureka-client

<!-- Redis -->
spring-boot-starter-data-redis

<!-- Feign Client -->
spring-cloud-starter-openfeign
```

---

## Configuration

### application.properties

```properties
# Server Configuration
spring.application.name=UserService
server.port=8081

# Database Configuration
spring.datasource.url=jdbc:mysql://localhost:3306/user_db
spring.datasource.username=root
spring.datasource.password=<your_password>
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# JPA/Hibernate Configuration
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.jpa.properties.hibernate.format_sql=true

# JWT Configuration
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000

# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms
```

### Configuration Details

| Property | Description | Default Value |
|----------|-------------|---------------|
| `server.port` | Port on which UserService runs | `8081` |
| `jwt.secret` | Base64 encoded secret key for JWT signing | Required |
| `jwt.expiration` | JWT token validity in milliseconds | `86400000` (24 hours) |
| `spring.data.redis.host` | Redis server hostname | `localhost` |
| `spring.data.redis.port` | Redis server port | `6379` |

### ⚠️ IMPORTANT FOR OTHER MICROSERVICES

**All microservices MUST use the same `jwt.secret` to validate JWT tokens.**

```properties
# Add to your microservice's application.properties
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

---

## Security

### SecurityConfig.java

The security configuration handles:

1. **Password Encoding** - BCrypt encryption
2. **Stateless Session** - No server-side session storage
3. **JWT Filter** - Validates tokens on every request
4. **Public Endpoints** - Registration and login don't require authentication

#### Public Endpoints (No Authentication Required)
```
POST /api/auth/register
POST /api/auth/login
/order/**
```

#### Protected Endpoints (JWT Required)
```
All other endpoints require valid JWT token in Authorization header
```

### How Security Filter Chain Works

```
Request → JwtAuthenticationFilter → Check Token → Validate → Set Authentication → Controller
                    ↓
            If blacklisted or invalid → 401 Unauthorized
```

### JwtAuthenticationFilter.java

This filter intercepts every HTTP request and:

1. Extracts JWT token from `Authorization: Bearer <token>` header
2. Checks if token is blacklisted (logged out) via Redis
3. Validates token signature and expiration
4. Loads user details from database
5. Sets authentication in SecurityContext

#### Methods

| Method | Description |
|--------|-------------|
| `doFilterInternal()` | Main filter method - processes each request |

#### Flow:
```java
1. Extract token from "Authorization" header
2. Check if token is NOT blacklisted (Redis lookup)
3. Extract username from token
4. Load UserDetails from database
5. Validate token (signature + expiration)
6. Create Authentication object
7. Set in SecurityContextHolder
8. Continue filter chain
```

### CustomUserDetailsService.java

Implements Spring Security's `UserDetailsService` to load users from the database.

#### Method: `loadUserByUsername(String username)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Load user from database for authentication |
| **Input** | `String username` |
| **Returns** | `UserDetails` object with username, password, authorities |
| **Throws** | `UsernameNotFoundException` if user not found |
| **Used By** | `JwtAuthenticationFilter`, `AuthenticationManager` |

---

## API Endpoints

### Authentication Endpoints

#### 1. Register User
```http
POST /api/auth/register
Content-Type: application/json

{
    "username": "john_doe",
    "email": "john@example.com",
    "password": "securePassword123",
    "role": "USER"
}
```

**Response:**
```
200 OK: "User Successfully Registered"
400 Bad Request: "Username already exists" / "Email already exists"
```

---

#### 2. Login
```http
POST /api/auth/login
Content-Type: application/json

{
    "username": "john_doe",
    "password": "securePassword123"
}
```

**Response:**
```json
{
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**JWT Token Claims:**
```json
{
    "sub": "john_doe",           // username (subject)
    "userId": "101",             // User ID as string
    "role": "USER",              // User role (USER or ADMIN)
    "email": "john@example.com", // User email
    "iat": 1616161616,           // Issued at timestamp
    "exp": 1616248016            // Expiration timestamp
}
```

---

#### 3. Logout
```http
POST /api/auth/logout
Authorization: Bearer <jwt_token>
```

**Response:**
```
200 OK: "Logged out successfully"
400 Bad Request: "Missing or invalid Authorization header"
```

**Behavior:** Token is added to Redis blacklist and cannot be used again.

---

#### 4. Get User by ID (For Order Service)
```http
GET /api/auth/user/{id}
Authorization: Bearer <jwt_token>
```

**Response (Format for Order Service):**
```json
{
    "id": 101,
    "name": "john_doe",
    "email": "john@example.com"
}
```

**Note:** This endpoint returns `name` (mapped from `username`) for Order Service compatibility.

---

#### 5. Get Current User Profile
```http
GET /api/auth/user/profile
Authorization: Bearer <jwt_token>
```

**Response:**
```json
{
    "id": 101,
    "username": "john_doe",
    "email": "john@example.com",
    "role": "USER"
}
```

**Note:** This endpoint extracts the user identity from the JWT token itself. No need to pass user ID.

---

#### 6. Update User Profile
```http
PUT /api/auth/user/profile
Authorization: Bearer <jwt_token>
Content-Type: application/json

{
    "username": "new_username",
    "email": "new_email@example.com",
    "password": "newPassword123"
}
```

**Note:** All fields are optional. Only provided fields will be updated.

---

## DTOs (Data Transfer Objects)

### CreateUserRequest
```java
{
    "username": String,    // Required - unique
    "email": String,       // Required - unique
    "password": String,    // Required
    "role": String         // Optional - e.g., "USER", "ADMIN"
}
```

### LoginRequest
```java
{
    "username": String,    // Required
    "password": String     // Required
}
```

### LoginResponse
```java
{
    "token": String        // JWT token with userId, role, email claims
}
```

### UpdateUser
```java
{
    "username": String,    // Optional - new username
    "email": String,       // Optional - new email
    "password": String     // Optional - new password (will be encoded)
}
```

### UserResponse
```java
{
    "id": Long,            // User ID (primary key)
    "username": String,    // Username
    "email": String,       // Email address
    "role": String         // User role
}

// Also provides getName() method that returns username
// for Order Service compatibility
```

---

## Service Layer Methods

### UserService Interface

| Method | Signature | Description |
|--------|-----------|-------------|
| `saveUser` | `void saveUser(CreateUserRequest request)` | Register new user |
| `getUserById` | `UserResponse getUserById(Long id)` | Get user by primary key |
| `getUserByUsername` | `UserResponse getUserByUsername(String username)` | Get user by username |
| `updateUserProfile` | `UserResponse updateUserProfile(String username, UpdateUser userDetails)` | Update user profile |

### UserServiceImpl - Detailed Method Documentation

#### 1. `saveUser(CreateUserRequest request)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Register a new user in the system |
| **Input** | `CreateUserRequest` with username, email, password, role |
| **Returns** | `void` |
| **Throws** | `IllegalArgumentException("Username already exists")` |
| | `IllegalArgumentException("Email already exists")` |

**Internal Methods Used:**
- `userRepository.existsByUsername()` - Check username uniqueness
- `userRepository.existsByEmail()` - Check email uniqueness
- `passwordEncoder.encode()` - BCrypt password hashing
- `userRepository.save()` - Persist user entity

**Flow:**
```
1. Check if username already exists → throw if true
2. Check if email already exists → throw if true
3. Create new User entity
4. Encode password using BCrypt
5. Save to database
```

---

#### 2. `getUserById(Long id)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Retrieve user by primary key |
| **Input** | `Long id` |
| **Returns** | `UserResponse` |
| **Throws** | `RuntimeException("User not found")` |
| **Caching** | `@Cacheable(value = "users", key = "#id")` |

**Internal Methods Used:**
- `userRepository.findById()` - Database lookup
- `mapToResponse()` - Entity to DTO conversion

---

#### 3. `getUserByUsername(String username)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Retrieve user by username |
| **Input** | `String username` |
| **Returns** | `UserResponse` |
| **Throws** | `RuntimeException("User not found")` |
| **Caching** | `@Cacheable(value = "usersByUsername", key = "#username")` |

**Internal Methods Used:**
- `userRepository.findByUsername()` - Database lookup
- `mapToResponse()` - Entity to DTO conversion

---

#### 4. `updateUserProfile(String username, UpdateUser userDetails)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Update existing user's profile |
| **Input** | `String username` - current username (from JWT), `UpdateUser userDetails` |
| **Returns** | `UserResponse` |
| **Throws** | `RuntimeException("User not found")` |
| | `IllegalArgumentException("Username already exists")` |
| | `IllegalArgumentException("Email already exists")` |
| **Cache Evict** | `@CacheEvict(value = {"users", "usersByUsername"}, allEntries = true)` |

**Internal Methods Used:**
- `userRepository.findByUsername()` - Load current user
- `userRepository.existsByUsername()` - Check new username uniqueness
- `userRepository.existsByEmail()` - Check new email uniqueness
- `passwordEncoder.encode()` - Encode new password
- `userRepository.save()` - Persist changes
- `mapToResponse()` - Entity to DTO conversion

**Flow:**
```
1. Load user by current username
2. If new username provided AND different:
   - Check uniqueness
   - Update username
3. If new email provided AND different:
   - Check uniqueness
   - Update email
4. If password provided:
   - Encode and update
5. Save and return response
6. Evict all caches
```

---

#### 5. `mapToResponse(User user)` [PRIVATE]

| Aspect | Details |
|--------|---------|
| **Purpose** | Convert User entity to UserResponse DTO |
| **Input** | `User entity` |
| **Returns** | `UserResponse` with id, username, email, role |

---

## Token Blacklist Service

### Purpose
Invalidate JWT tokens on logout without waiting for natural expiration. Uses Redis for multi-instance support.

### Interface: TokenBlacklistService

```java
public interface TokenBlacklistService {
    void blacklistToken(String token, Date expiresAt);
    boolean isBlacklisted(String token);
}
```

### Implementation: RedisTokenBlacklistService (Primary)

Uses Redis for distributed token blacklisting across all service instances.

#### Method: `blacklistToken(String token, Date expiresAt)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Add a token to the Redis blacklist |
| **Input** | `String token` - JWT token, `Date expiresAt` - token expiration |
| **Behavior** | Stores token in Redis with TTL matching token expiration |
| **Key Format** | `blacklist:{token}` |
| **Validation** | Ignores null/blank tokens or null expiration |

---

#### Method: `isBlacklisted(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Check if token is blacklisted in Redis |
| **Input** | `String token` - JWT token to check |
| **Returns** | `true` if blacklisted, `false` otherwise |
| **Auto-Cleanup** | Redis automatically removes expired keys (TTL) |

### Implementation: InMemoryTokenBlacklistService (Deprecated)

⚠️ **DEPRECATED** - Only for single-instance development. Uses `ConcurrentHashMap`.

---

## JWT Utility Methods

### JwtUtil.java

Central utility class for all JWT operations.

### Methods

#### 1. `generateToken(User user)` ✅ NEW - Use This

| Aspect | Details |
|--------|---------|
| **Purpose** | Create new JWT token with full claims |
| **Input** | `User user` - Full user entity |
| **Returns** | `String` - Signed JWT token |
| **Token Claims** | subject (username), userId, role, email, issuedAt, expiration |
| **Validity** | Configured by `jwt.expiration` property (default: 24 hours) |

**Token Structure:**
```
Header: {"alg": "HS256", "typ": "JWT"}
Payload: {
    "sub": "username",
    "userId": "101",
    "role": "USER",
    "email": "user@example.com",
    "iat": <timestamp>,
    "exp": <timestamp + expiration>
}
Signature: HMACSHA256(header + payload, secret)
```

---

#### 2. `generateToken(String username)` ⚠️ DEPRECATED

| Aspect | Details |
|--------|---------|
| **Purpose** | Create basic JWT token (username only) |
| **Status** | DEPRECATED - Use `generateToken(User user)` instead |

---

#### 3. `validateToken(String token, String username)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Validate token authenticity and expiration |
| **Input** | `String token`, `String username` |
| **Returns** | `boolean` - true if valid |
| **Checks** | 1. Username matches token subject |
| | 2. Token is not expired |

---

#### 4. `extractUsername(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Get username from token (subject claim) |
| **Input** | `String token` |
| **Returns** | `String` - username |
| **Throws** | `JwtException` if token is invalid |

---

#### 5. `extractUserId(String token)` ✅ NEW

| Aspect | Details |
|--------|---------|
| **Purpose** | Get userId from token |
| **Input** | `String token` |
| **Returns** | `Long` - user ID |

---

#### 6. `extractRole(String token)` ✅ NEW

| Aspect | Details |
|--------|---------|
| **Purpose** | Get role from token |
| **Input** | `String token` |
| **Returns** | `String` - user role (USER, ADMIN) |

---

#### 7. `extractEmail(String token)` ✅ NEW

| Aspect | Details |
|--------|---------|
| **Purpose** | Get email from token |
| **Input** | `String token` |
| **Returns** | `String` - user email |

---

#### 8. `extractExpiration(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Get expiration date from token |
| **Input** | `String token` |
| **Returns** | `Date` - expiration timestamp |

---

#### 9. `extractTokenFromHeader(String authHeader)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Extract token from Authorization header |
| **Input** | `String authHeader` - e.g., "Bearer eyJ..." |
| **Returns** | `String` - token without "Bearer " prefix |
| **Returns** | `null` if header is null or doesn't start with "Bearer " |

---

## Integration Guide for Other Microservices

### How to Validate JWT Tokens in Other Services

#### Step 1: Add JWT Dependencies to pom.xml

```xml
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
```

#### Step 2: Add Configuration to application.properties

```properties
# MUST BE SAME AS USERSERVICE
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

#### Step 3: Create JwtUtil Class

Copy this utility class to your microservice:

```java
package com.yourservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String SECRET_KEY;

    public boolean validateToken(String token, String username) {
        String tokenUsername = extractUsername(token);
        return (username.equals(tokenUsername) && !isTokenExpired(token));
    }

    public String extractUsername(String token) {
        return getClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        String userId = getClaims(token).get("userId", String.class);
        return userId != null ? Long.parseLong(userId) : null;
    }

    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    public String extractEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public Date extractExpiration(String token) {
        return getClaims(token).getExpiration();
    }

    public String extractTokenFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return authHeader.substring(7);
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isTokenExpired(String token) {
        Date expirationDate = extractExpiration(token);
        return expirationDate.before(new Date());
    }
}
```

#### Step 4: Create JWT Filter

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String token = jwtUtil.extractTokenFromHeader(
            request.getHeader("Authorization")
        );

        if (token != null) {
            try {
                String username = jwtUtil.extractUsername(token);
                Long userId = jwtUtil.extractUserId(token);
                String role = jwtUtil.extractRole(token);
                
                // Create authentication with role-based authorities
                List<SimpleGrantedAuthority> authorities = 
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role));
                
                UsernamePasswordAuthenticationToken authToken = 
                    new UsernamePasswordAuthenticationToken(
                        username, null, authorities
                    );
                
                // Store userId in request attributes for easy access
                request.setAttribute("userId", userId);
                
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (Exception ex) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### How to Get Current User (Without User ID from Client)

**Problem:** End users shouldn't pass user ID. It should be extracted from JWT.

**Solution 1:** Extract from JWT token in your controller:

```java
@RestController
public class YourController {

    private final JwtUtil jwtUtil;

    @GetMapping("/your-endpoint")
    public ResponseEntity<?> yourMethod(
            @RequestHeader("Authorization") String authHeader) {
        
        // Extract token and user info
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        String username = jwtUtil.extractUsername(token);
        Long userId = jwtUtil.extractUserId(token);
        String role = jwtUtil.extractRole(token);
        
        // Now you have the authenticated user's info
        // Use it to call UserService or process your logic
    }
}
```

**Solution 2:** Use Spring Security's Principal:

```java
@GetMapping("/your-endpoint")
public ResponseEntity<?> yourMethod(Principal principal) {
    String username = principal.getName();
    // Use username...
}
```

**Solution 3:** Use request attribute (if set in filter):

```java
@GetMapping("/your-endpoint")
public ResponseEntity<?> yourMethod(HttpServletRequest request) {
    Long userId = (Long) request.getAttribute("userId");
    // Use userId...
}
```

### Calling UserService from Other Microservices

#### Using RestTemplate

```java
@Service
public class UserServiceClient {

    private final RestTemplate restTemplate;
    
    public Map<String, Object> getUserById(Long id, String jwtToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtToken);
        
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        ResponseEntity<Map> response = restTemplate.exchange(
            "http://UserService/api/auth/user/" + id,
            HttpMethod.GET,
            entity,
            Map.class
        );
        
        return response.getBody();
    }
}
```

#### Using Feign Client (Recommended)

```java
@FeignClient(name = "UserService")
public interface UserServiceClient {

    @GetMapping("/api/auth/user/{id}")
    Map<String, Object> getUserById(
        @PathVariable("id") Long id,
        @RequestHeader("Authorization") String token
    );
    
    @GetMapping("/api/auth/user/profile")
    UserResponse getCurrentUserProfile(
        @RequestHeader("Authorization") String token
    );
}
```

Don't forget to add `@EnableFeignClients` to your main application class.

---

## Error Handling

### GlobalExceptionHandler.java

Centralized exception handling using `@RestControllerAdvice`.

### Exception Types

| Exception | Cause | HTTP Status | Response |
|-----------|-------|-------------|----------|
| `IllegalArgumentException` | Username/Email already exists | 400 Bad Request | Error details with message |
| `UsernameNotFoundException` | User not found during lookup | 404 Not Found | Error details with message |
| `BadCredentialsException` | Invalid login credentials | 401 Unauthorized | "Invalid username or password" |
| `RuntimeException` | General server errors | 500 Internal Server Error | Error details with message |

### Error Response Format

```json
{
    "timestamp": "2026-04-29T10:30:00",
    "status": 400,
    "error": "Bad Request",
    "message": "Username already exists"
}
```

---

## Database Schema

### Table: `users`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PRIMARY KEY, AUTO_INCREMENT | User ID |
| `user_name` | VARCHAR | NOT NULL | Username |
| `email` | VARCHAR | NOT NULL, UNIQUE | Email address |
| `password` | VARCHAR | NOT NULL | BCrypt hashed password |
| `role` | VARCHAR | | User role (USER, ADMIN, etc.) |

### Entity: User.java

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    
    @NonNull
    @Column(name = "user_name")
    private String username;
    
    @NonNull
    @Column(unique = true)
    private String email;
    
    @NonNull
    private String password;
    
    private String role;
}
```

---

## Redis Configuration

### RedisConfig.java

Configures Redis cache management with custom TTLs.

```java
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        // Default TTL: 10 minutes
        // Uses JSON serialization for values
        // Caches: "users", "usersByUsername"
    }
}
```

### Cache Strategy Summary

| Cache Name | Key Pattern | TTL | Eviction Trigger |
|------------|-------------|-----|------------------|
| `users` | `{userId}` | 10 min | Profile update |
| `usersByUsername` | `{username}` | 10 min | Profile update |
| `blacklist:{token}` | Token string | Token TTL | Logout |

---

## Feign Client Setup

### OrderClient.java

Feign client for communicating with Order Service.

```java
@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);
}
```

### OrderResponse DTO

```java
public class OrderResponse {
    private Long id;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
```

### OrderItem DTO

```java
public class OrderItem {
    private Long productId;
    private String productName;
    private Integer quantity;
    private Double price;
}
```

---

## Repository Methods

### UserRepository

| Method | Return Type | Description |
|--------|-------------|-------------|
| `findById(Long id)` | `Optional<User>` | Find by primary key |
| `findByUsername(String username)` | `Optional<User>` | Find by username |
| `findByEmail(String email)` | `Optional<User>` | Find by email |
| `existsByUsername(String username)` | `boolean` | Check username exists |
| `existsByEmail(String email)` | `boolean` | Check email exists |
| `save(User user)` | `User` | Save/update user |

---

## Quick Reference Card

### Authentication Flow

```
1. Register: POST /api/auth/register
2. Login: POST /api/auth/login → Get JWT token (with userId, role, email)
3. Use token: Authorization: Bearer <token>
4. Logout: POST /api/auth/logout → Token blacklisted in Redis
```

### Token Format

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VybmFtZSIsInVzZXJJZCI6IjEwMSIsInJvbGUiOiJVU0VSIiwiZW1haWwiOiJ1c2VyQGV4YW1wbGUuY29tIiwiaWF0IjoxNjE2MTYxNjE2LCJleHAiOjE2MTYyNDgwMTZ9.signature
```

### Key Configuration for Integration

```properties
# ALL MICROSERVICES MUST HAVE THESE:
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

### Project Structure

```
UserService/
├── src/main/java/com/onlineShopping/UserService/
│   ├── UserServiceApplication.java          # @EnableFeignClients, @EnableDiscoveryClient
│   ├── client/
│   │   └── OrderClient.java                 # Feign client for Order Service
│   ├── config/
│   │   └── RedisConfig.java                 # Redis cache configuration
│   ├── Controller/
│   │   └── AuthController.java              # REST endpoints
│   ├── dto/
│   │   ├── CreateUserRequest.java
│   │   ├── LoginRequest.java
│   │   ├── LoginResponse.java
│   │   ├── OrderItem.java
│   │   ├── OrderResponse.java
│   │   ├── UpdateUser.java
│   │   └── UserResponse.java                # Includes email, getName() alias
│   ├── entity/
│   │   └── User.java
│   ├── excpetion/
│   │   └── GlobalExceptionHandler.java
│   ├── repository/
│   │   └── UserRepository.java
│   ├── security/
│   │   ├── CustomUserDetailsService.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtUtil.java                     # Includes userId, role, email extraction
│   │   └── SecurityConfig.java
│   ├── service/
│   │   ├── TokenBlacklistService.java       # Interface
│   │   └── UserService.java                 # Interface
│   └── serviceimpl/
│       ├── InMemoryTokenBlacklistService.java  # @Deprecated
│       ├── RedisTokenBlacklistService.java     # @Primary - Use this
│       └── UserServiceImpl.java                # @Cacheable annotations
└── src/main/resources/
    └── application.properties               # JWT + Redis config
```

---

## Contact & Support

- **Service Port:** 8081
- **Eureka Service Name:** UserService
- **Health Check:** http://localhost:8081/actuator/health

---

*Last Updated: April 29, 2026*

