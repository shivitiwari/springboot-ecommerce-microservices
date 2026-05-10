# UserService - Microservice Documentation

> **Version:** 0.0.1-SNAPSHOT  
> **Port:** 8081  
> **Base URL:** `http://localhost:8081`

This document provides comprehensive documentation for the **UserService** microservice in the Online Shopping platform. It is designed to help other microservices understand how to integrate with this service.

---

## ⚠️ REQUIRED CHANGES - Integration Checklist

> **Last Updated:** May 7, 2026  
> **Status:** Requires critical changes for multi-instance deployment

### 🔴 CRITICAL - Must Implement

#### 1. Stop Re-Parsing JWT in Protected Endpoints — Use Gateway Headers Instead

**Issue:** `getUserProfile` and `updateUserProfile` in `AuthController` are reading the `Authorization`
header and re-parsing the JWT themselves. This violates the architecture:

```
Architecture rule:
  API Gateway  → validates JWT → extracts claims → forwards X-User-Id, X-Username, X-User-Role
  Downstream   → reads X-User headers only       → NEVER re-parse JWT
```

**Current Implementation (WRONG):**
```java
// getUserProfile
String token = jwtUtil.extractTokenFromHeader(authorizationHeader);  // re-parsing ✗
String username = jwtUtil.extractUsername(token);

// updateUserProfile
String token = jwtUtil.extractTokenFromHeader(authorizationHeader);  // re-parsing ✗
String username = jwtUtil.extractUsername(token);
```

**Fix — Update `AuthController.java`:**
```java
// Add HttpServletRequest import
import jakarta.servlet.http.HttpServletRequest;

// getUserProfile — read X-Username forwarded by Gateway
@GetMapping("user/profile")
public ResponseEntity<?> getUserProfile(HttpServletRequest request) {
    try {
        String username = request.getHeader("X-Username");  // forwarded by Gateway ✓
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
        }
        return ResponseEntity.ok(userService.getUserByUsername(username));
    } catch (Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to get user profile: " + e.getMessage());
    }
}

// updateUserProfile — read X-Username forwarded by Gateway
@PutMapping("user/profile")
public ResponseEntity<?> updateUserProfile(
        HttpServletRequest request,
        @RequestBody UpdateUser userDetails) {
    try {
        String username = request.getHeader("X-Username");  // forwarded by Gateway ✓
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
        }
        return ResponseEntity.ok(userService.updateUserProfile(username, userDetails));
    } catch (Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to update user profile: " + e.getMessage());
    }
}
```

> **Why?**
> - Gateway already validated the JWT signature + blacklist check
> - Re-parsing JWT in the service = double work + tight coupling to JWT secret
> - If you rotate the JWT secret, service breaks independently
> - `X-Username` is set by Gateway only after full validation — safe to trust
>
> **Exception — logout stays as-is:**
> Logout MUST still read the `Authorization` header because it needs the raw token string
> to blacklist it in Redis. `X-Username` alone is not enough for blacklisting.

---

#### 2. Replace InMemory Token Blacklist with Redis

**Issue:** Current `InMemoryTokenBlacklistService` won't work with multiple service instances. Logged-out tokens will only be blacklisted on one instance.

**Current Implementation (PROBLEMATIC):**
```java
// InMemoryTokenBlacklistService uses ConcurrentHashMap
// This is NOT shared across instances!
private final ConcurrentHashMap<String, Date> blacklist = new ConcurrentHashMap<>();
```

**Add Dependency:** `pom.xml`
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
```

**Create File:** `src/main/java/com/onlineshopping/userservice/service/RedisTokenBlacklistService.java`

```java
package com.onlineshopping.userservice.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String BLACKLIST_PREFIX = "blacklist:";

    @Override
    public void blacklistToken(String token, Date expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return;
        }
        
        long ttlMillis = expiresAt.getTime() - System.currentTimeMillis();
        if (ttlMillis > 0) {
            String key = BLACKLIST_PREFIX + token;
            redisTemplate.opsForValue().set(key, "blacklisted", ttlMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = BLACKLIST_PREFIX + token;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
```

**Update:** Remove or deprecate `InMemoryTokenBlacklistService.java`

#### 2. Add Required JWT Claims for API Gateway

**Issue:** API Gateway expects `userId` and `role` claims in JWT. Ensure these are included when generating tokens.

**Verify/Update:** `src/main/java/com/onlineshopping/userservice/security/JwtUtil.java`

```java
public String generateToken(User user) {
    return Jwts.builder()
            .subject(user.getUsername())
            .claim("userId", String.valueOf(user.getId()))  // REQUIRED for API Gateway
            .claim("role", user.getRole())                   // REQUIRED for API Gateway (USER or ADMIN)
            .claim("email", user.getEmail())                 // Optional
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(getSigningKey())
            .compact();
}
```

**Verify Response DTO:** Ensure `UserResponse` matches what Order Service expects:

```java
// Order Service expects: { "id", "name", "email" }
// User Service returns:  { "id", "username", "role" }

// Option 1: Add endpoint that returns expected format
@GetMapping("/api/auth/user/{id}")
public ResponseEntity<?> getUserForOrderService(@PathVariable Long id) {
    User user = userRepository.findById(id).orElseThrow();
    return ResponseEntity.ok(Map.of(
        "id", user.getId(),
        "name", user.getUsername(),  // Map username to name
        "email", user.getEmail()
    ));
}

// Option 2: Update UserResponse DTO to include 'name' alias
public class UserResponse {
    private Long id;
    private String username;
    private String role;
    
    // Alias for Order Service compatibility
    public String getName() {
        return username;
    }
}
```

---

### 🟡 IMPORTANT - Should Implement

#### 3. Add Redis Caching for User Lookups

**Issue:** User lookups hit database every time. Add caching for frequently accessed data.

**Create File:** `src/main/java/com/onlineshopping/userservice/config/RedisConfig.java`

```java
package com.onlineshopping.userservice.config;

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
            .entryTtl(Duration.ofMinutes(10))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()))
            .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();
        cacheConfigs.put("users", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigs.put("usersByUsername", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }
}
```

**Update Service Implementation:** Add `@Cacheable` annotations

```java
@Service
public class UserServiceImpl implements UserService {

    @Cacheable(value = "users", key = "#id")
    public UserResponse getUserById(Long id) {
        // ... existing code
    }

    @Cacheable(value = "usersByUsername", key = "#username")
    public UserResponse getUserByUsername(String username) {
        // ... existing code
    }

    @CacheEvict(value = {"users", "usersByUsername"}, allEntries = true)
    public UserResponse updateUserProfile(String username, UpdateUser userDetails) {
        // ... existing code
    }
}
```

#### 4. Add Feign Client for Order Service (Optional)

**Issue:** User Service could call Order Service to show order history on user profile.

**Add Dependency:** `pom.xml`
```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

**Create File:** `src/main/java/com/onlineshopping/userservice/client/OrderClient.java`

```java
package com.onlineshopping.userservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);
}
```

**Enable Feign:** Add `@EnableFeignClients` to main application class.

#### 5. Add Global Exception Handler

**Create File:** `src/main/java/com/onlineshopping/userservice/exception/GlobalExceptionHandler.java`

```java
package com.onlineshopping.userservice.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 400,
            "error", "Bad Request",
            "message", e.getMessage()
        ));
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUserNotFound(UsernameNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 404,
            "error", "Not Found",
            "message", e.getMessage()
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 401,
            "error", "Unauthorized",
            "message", "Invalid username or password"
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "timestamp", LocalDateTime.now(),
            "status", 500,
            "error", "Internal Server Error",
            "message", e.getMessage()
        ));
    }
}
```

---

### 🟢 NICE TO HAVE - Optional Improvements

#### 6. Add Email Verification (Not Implemented)

Consider adding email verification for new user registrations.

#### 7. Add Password Reset Flow (Not Implemented)

Consider adding forgot password / reset password functionality.

#### 8. Add Rate Limiting for Auth Endpoints

Prevent brute force attacks on login endpoint.

```java
// Add to SecurityConfig or create separate filter
// Limit login attempts to 5 per minute per IP
```

---

### 📋 Updated Project Structure (After Changes)

```
user-service/
├── src/main/java/com/onlineshopping/userservice/
│   ├── UserServiceApplication.java          # ADD @EnableFeignClients (if adding OrderClient)
│   ├── client/                              # NEW (optional)
│   │   └── OrderClient.java                 # NEW - Feign client for Order Service
│   ├── config/
│   │   └── RedisConfig.java                 # NEW - Cache configuration
│   ├── controller/
│   │   └── AuthController.java              # MODIFY - Add endpoint for Order Service
│   ├── dto/
│   │   └── UserResponse.java                # MODIFY - Add getName() alias
│   ├── exception/                           # NEW
│   │   └── GlobalExceptionHandler.java      # NEW
│   ├── security/
│   │   ├── JwtUtil.java                     # VERIFY - Ensure userId & role claims
│   │   └── JwtAuthenticationFilter.java     # EXISTS ✅
│   └── service/
│       ├── TokenBlacklistService.java       # Interface - keep as is
│       ├── InMemoryTokenBlacklistService.java  # DEPRECATED - Remove
│       ├── RedisTokenBlacklistService.java  # NEW - Replace InMemory
│       └── UserServiceImpl.java             # MODIFY - Add @Cacheable
└── src/main/resources/
    └── application.properties               # MODIFY - Add Redis config
```

---

### 📊 Cache Strategy Summary

| Cache Name | Key Pattern | TTL | Eviction Trigger |
|------------|-------------|-----|------------------|
| `users` | `{userId}` | 10 min | Profile update |
| `usersByUsername` | `{username}` | 10 min | Profile update |
| `blacklist:{token}` | Token string | Token TTL | Logout |

---

### 🔗 Required Endpoint for Order Service

Order Service calls `GET /api/auth/user/{id}` expecting this response:

```json
{
  "id": 101,
  "name": "John Doe",
  "email": "john@example.com"
}
```

**Ensure this endpoint exists and returns the correct format!**

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

---

## Overview

UserService handles:
- **User Registration** - Create new user accounts
- **User Authentication** - Login with JWT token generation
- **User Logout** - Token blacklisting for secure logout
- **User Profile Management** - Get and update user profiles
- **JWT Token Validation** - Validate tokens for other microservices

---

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Framework | Spring Boot | 3.2.5 |
| Java | JDK | 21 |
| Database | MySQL | Latest |
| Security | Spring Security + JWT | jjwt 0.12.6 |
| Service Discovery | Eureka Client | 2023.0.1 |
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
jwt.secret=<base64_encoded_secret_key>
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
2. Checks if token is blacklisted (logged out)
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
2. Check if token is NOT blacklisted
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

**Behavior:** Token is added to blacklist and cannot be used again.

---

#### 4. Get User by ID
```http
GET /api/auth/user/{id}
Authorization: Bearer <jwt_token>
```

**Response:**
```
200 OK: "User found"
500 Internal Server Error: "Failed to get user id: <error>"
```

---

#### 5. Get Current User Profile
```http
GET /api/auth/user/profile
Authorization: Bearer <jwt_token>
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
    "token": String        // JWT token
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
    "role": String         // User role
}
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
```

---

#### 5. `mapToResponse(User user)` [PRIVATE]

| Aspect | Details |
|--------|---------|
| **Purpose** | Convert User entity to UserResponse DTO |
| **Input** | `User entity` |
| **Returns** | `UserResponse` with id, username, role |

---

## Token Blacklist Service

### Purpose
Invalidate JWT tokens on logout without waiting for natural expiration.

### Interface: TokenBlacklistService

```java
public interface TokenBlacklistService {
    void blacklistToken(String token, Date expiresAt);
    boolean isBlacklisted(String token);
}
```

### Implementation: InMemoryTokenBlacklistService

Uses `ConcurrentHashMap` for thread-safe in-memory storage.

#### Method: `blacklistToken(String token, Date expiresAt)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Add a token to the blacklist |
| **Input** | `String token` - JWT token, `Date expiresAt` - token expiration |
| **Behavior** | Stores token with its expiration timestamp |
| **Validation** | Ignores null/blank tokens or null expiration |

---

#### Method: `isBlacklisted(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Check if token is blacklisted |
| **Input** | `String token` - JWT token to check |
| **Returns** | `true` if blacklisted and not expired, `false` otherwise |
| **Auto-Cleanup** | Removes expired tokens from blacklist automatically |

**Flow:**
```
1. Look up token in blacklist map
2. If not found → return false
3. If found but expired → remove from map, return false
4. If found and not expired → return true (blacklisted)
```

### ⚠️ Production Consideration

**Current implementation is IN-MEMORY.** For production microservices architecture:

```java
// Replace with Redis implementation:
@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {
    
    private final RedisTemplate<String, String> redisTemplate;
    
    @Override
    public void blacklistToken(String token, Date expiresAt) {
        long ttl = expiresAt.getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(
                "blacklist:" + token, 
                "blacklisted", 
                ttl, 
                TimeUnit.MILLISECONDS
            );
        }
    }
    
    @Override
    public boolean isBlacklisted(String token) {
        return redisTemplate.hasKey("blacklist:" + token);
    }
}
```

**Required Dependencies for Redis:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## JWT Utility Methods

### JwtUtil.java

Central utility class for all JWT operations.

### Methods

#### 1. `generateToken(String username)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Create new JWT token |
| **Input** | `String username` |
| **Returns** | `String` - Signed JWT token |
| **Token Contains** | subject (username), issuedAt, expiration |
| **Validity** | Configured by `jwt.expiration` property (default: 24 hours) |

**Token Structure:**
```
Header: {"alg": "HS256", "typ": "JWT"}
Payload: {
    "sub": "username",
    "iat": <timestamp>,
    "exp": <timestamp + expiration>
}
Signature: HMACSHA256(header + payload, secret)
```

---

#### 2. `validateToken(String token, String username)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Validate token authenticity and expiration |
| **Input** | `String token`, `String username` |
| **Returns** | `boolean` - true if valid |
| **Checks** | 1. Username matches token subject |
| | 2. Token is not expired |

---

#### 3. `extractUsername(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Get username from token |
| **Input** | `String token` |
| **Returns** | `String` - username (subject claim) |
| **Throws** | `JwtException` if token is invalid |

---

#### 4. `extractExpiration(String token)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Get expiration date from token |
| **Input** | `String token` |
| **Returns** | `Date` - expiration timestamp |

---

#### 5. `extractTokenFromHeader(String authHeader)`

| Aspect | Details |
|--------|---------|
| **Purpose** | Extract token from Authorization header |
| **Input** | `String authHeader` - e.g., "Bearer eyJ..." |
| **Returns** | `String` - token without "Bearer " prefix |
| **Returns** | `null` if header is null or doesn't start with "Bearer " |

---

## Integration Guide for Other Microservices

### How to Validate JWT Tokens in Other Services

#### Step 1: Add JWT Dependencies

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

#### Step 2: Add Configuration

```properties
# MUST BE SAME AS USERSERVICE
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

#### Step 3: Copy JwtUtil Class

Copy `JwtUtil.java` to your microservice or create a shared library.

#### Step 4: Create JWT Filter

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) {
        
        String token = jwtUtil.extractTokenFromHeader(
            request.getHeader("Authorization")
        );

        if (token != null) {
            try {
                String username = jwtUtil.extractUsername(token);
                
                // Create authentication
                UsernamePasswordAuthenticationToken authToken = 
                    new UsernamePasswordAuthenticationToken(
                        username, null, Collections.emptyList()
                    );
                
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } catch (JwtException ex) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

### How to Get Current User (Without User ID)

**Problem:** End users shouldn't pass user ID. It should be extracted from JWT.

**Solution:** Extract username from JWT token in your controller:

```java
@RestController
public class YourController {

    @GetMapping("/your-endpoint")
    public ResponseEntity<?> yourMethod(
            @RequestHeader("Authorization") String authHeader,
            JwtUtil jwtUtil) {
        
        // Extract token and username
        String token = jwtUtil.extractTokenFromHeader(authHeader);
        String username = jwtUtil.extractUsername(token);
        
        // Now you have the authenticated user's username
        // Use it to call UserService or process your logic
    }
}
```

**Or use Spring Security's Principal:**

```java
@GetMapping("/your-endpoint")
public ResponseEntity<?> yourMethod(Principal principal) {
    String username = principal.getName();
    // Use username...
}
```

**Or use @AuthenticationPrincipal:**

```java
@GetMapping("/your-endpoint")
public ResponseEntity<?> yourMethod(
        @AuthenticationPrincipal UserDetails userDetails) {
    String username = userDetails.getUsername();
    // Use username...
}
```

### Calling UserService from Other Microservices

#### Using RestTemplate

```java
@Service
public class UserServiceClient {

    private final RestTemplate restTemplate;
    
    public UserResponse getUserByUsername(String username, String jwtToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtToken);
        
        HttpEntity<?> entity = new HttpEntity<>(headers);
        
        ResponseEntity<UserResponse> response = restTemplate.exchange(
            "http://UserService/api/auth/user/profile",
            HttpMethod.GET,
            entity,
            UserResponse.class
        );
        
        return response.getBody();
    }
}
```

#### Using Feign Client (Recommended for Microservices)

```java
@FeignClient(name = "UserService")
public interface UserServiceClient {

    @GetMapping("/api/auth/user/{id}")
    UserResponse getUserById(
        @PathVariable("id") Long id,
        @RequestHeader("Authorization") String token
    );
    
    @GetMapping("/api/auth/user/profile")
    UserResponse getCurrentUserProfile(
        @RequestHeader("Authorization") String token
    );
}
```

---

## Error Handling

### Exception Types

| Exception | Cause | HTTP Status |
|-----------|-------|-------------|
| `IllegalArgumentException` | Username/Email already exists | 400 Bad Request |
| `RuntimeException` | User not found | 500 Internal Server Error |
| `UsernameNotFoundException` | User not found during auth | 401 Unauthorized |
| `JwtException` | Invalid/expired token | 401 Unauthorized |

### Recommended Error Response Format

```json
{
    "timestamp": "2026-04-20T10:30:00",
    "status": 400,
    "error": "Bad Request",
    "message": "Username already exists",
    "path": "/api/auth/register"
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
2. Login: POST /api/auth/login → Get JWT token
3. Use token: Authorization: Bearer <token>
4. Logout: POST /api/auth/logout → Token blacklisted
```

### Token Format

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VybmFtZSIsImlhdCI6MTYxNjE2MTYxNiwiZXhwIjoxNjE2MjQ4MDE2fQ.signature
```

### Key Configuration for Integration

```properties
# ALL MICROSERVICES MUST HAVE THESE:
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

---

## Contact & Support

- **Service Port:** 8081
- **Eureka Service Name:** UserService
- **Health Check:** http://localhost:8081/actuator/health

---

*Last Updated: May 7, 2026*

---

## ⚠️ MISSING IMPLEMENTATIONS — Must Add

### 🔴 CRITICAL

#### A. SecurityConfig.java — Not Shown, Must Verify

User Service needs a `SecurityConfig` that:
- Permits `/api/auth/register` and `/api/auth/login` without token
- Requires auth for all other endpoints
- Wires up `JwtAuthenticationFilter` (existing)
- Disables CSRF (stateless API)

**Verify/Create:** `src/main/java/com/onlineshopping/userservice/security/SecurityConfig.java`
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthFilter;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

#### B. Fix getUserProfile and updateUserProfile — Remove JWT Re-parsing

**These methods MUST use `X-Username` header (forwarded by Gateway) instead of re-parsing JWT:**

```java
// WRONG (current) — re-parses JWT in service:
String token = jwtUtil.extractTokenFromHeader(authorizationHeader);
String username = jwtUtil.extractUsername(token);

// CORRECT — trust Gateway header:
@GetMapping("user/profile")
public ResponseEntity<?> getUserProfile(HttpServletRequest request) {
    String username = request.getHeader("X-Username");
    if (username == null || username.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
    }
    return ResponseEntity.ok(userService.getUserByUsername(username));
}

@PutMapping("user/profile")
public ResponseEntity<?> updateUserProfile(HttpServletRequest request,
                                            @RequestBody UpdateUser userDetails) {
    String username = request.getHeader("X-Username");
    if (username == null || username.isBlank()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
    }
    return ResponseEntity.ok(userService.updateUserProfile(username, userDetails));
}
```

> **NOTE:** `logout` endpoint correctly reads Authorization header — it needs the raw token string to blacklist it. Do NOT change logout.

#### C. Replace InMemoryTokenBlacklistService with Redis

See Section 1 (REQUIRED CHANGES) for full implementation.

---

### 🟡 IMPORTANT

#### D. JWT Claims — Must Include userId and role

**Verify `JwtUtil.generateToken()` includes these claims:**
```java
public String generateToken(User user) {
    return Jwts.builder()
        .subject(user.getUsername())
        .claim("userId", String.valueOf(user.getId()))   // ← API Gateway REQUIRES this
        .claim("role", user.getRole())                    // ← API Gateway REQUIRES this
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
        .signWith(getSigningKey())
        .compact();
}
```

If `userId` or `role` claims are missing, API Gateway's `JwtAuthFilter` cannot forward
`X-User-Id` and `X-User-Role` headers, breaking Product and Order service authorization.

#### E. JWT Secret Must Match Gateway
```properties
# application.properties — MUST be identical to API Gateway
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
jwt.expiration=86400000
```

---

## 🧪 Postman Testing Guide

> **All requests go through API Gateway at `http://localhost:8080`**
> Direct service calls to `http://localhost:8081` bypass JWT validation and rate limiting.

### Prerequisites — Start services in this order:
1. Redis (`localhost:6379`)
2. MySQL (`localhost:3306`)
3. Eureka Server (`localhost:8761`)
4. User Service (`localhost:8081`)
5. API Gateway (`localhost:8080`)

---

### 1. Register User
```
Method:  POST
URL:     http://localhost:8080/api/auth/register
Headers: Content-Type: application/json

Body (raw JSON):
{
    "username": "john_doe",
    "email": "john@example.com",
    "password": "password123",
    "role": "USER"
}

Expected: 200 OK — "User Successfully Registered"
Error:    400 if username/email already exists
Rate Limit: 10 requests/min (Gateway)
```

### 2. Register Admin User
```
Method:  POST
URL:     http://localhost:8080/api/auth/register
Headers: Content-Type: application/json

Body (raw JSON):
{
    "username": "admin_user",
    "email": "admin@example.com",
    "password": "admin123",
    "role": "ADMIN"
}

Expected: 200 OK — "User Successfully Registered"
```

### 3. Login (Get JWT Token)
```
Method:  POST
URL:     http://localhost:8080/api/auth/login
Headers: Content-Type: application/json

Body (raw JSON):
{
    "username": "john_doe",
    "password": "password123"
}

Expected Response:
{
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huX2RvZSIsInVzZXJJZCI6IjEiLCJyb2xlIjoiVVNFUiJ9.xxxx"
}

⚠️ SAVE THIS TOKEN — use as Bearer token in all protected requests
Rate Limit: 5 requests/min (Gateway — brute-force protection)
```

### 4. Logout
```
Method:  POST
URL:     http://localhost:8080/api/auth/logout
Headers: Authorization: Bearer <your_jwt_token>

Expected: 200 OK — "Logged out successfully"
Effect:   Token is blacklisted in Redis — cannot be used again
Error:    400 if no Authorization header
```

### 5. Get User by ID (Used by Order Service internally)
```
Method:  GET
URL:     http://localhost:8080/api/auth/user/1
Headers: Authorization: Bearer <your_jwt_token>

Expected Response:
{
    "id": 1,
    "name": "john_doe",
    "email": "john@example.com"
}

Note: "name" field maps from username — Order Service depends on this format
```

### 6. Get My Profile
```
Method:  GET
URL:     http://localhost:8080/api/auth/user/profile
Headers: Authorization: Bearer <your_jwt_token>

Expected Response: UserResponse with your profile data
Note: Gateway extracts X-Username from JWT and forwards it — service reads X-Username header
```

### 7. Update My Profile
```
Method:  PUT
URL:     http://localhost:8080/api/auth/user/profile
Headers:
  Authorization: Bearer <your_jwt_token>
  Content-Type: application/json

Body (raw JSON) — all fields optional:
{
    "email": "new_email@example.com",
    "password": "newPassword123"
}

Expected: 200 OK with updated UserResponse
```

---

### ❌ Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `401 Unauthorized` | Missing/invalid/expired token | Re-login to get new token |
| `401 Unauthorized` with valid token | Token was blacklisted (logged out) | Login again |
| `429 Too Many Requests` | Rate limit hit | Wait 60s (login: 5/min, register: 10/min) |
| `400 Bad Request` on register | Username or email already exists | Use different username/email |
| `500 Internal Server Error` | User not found | Check userId exists in DB |

