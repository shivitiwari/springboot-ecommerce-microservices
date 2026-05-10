# UserService - Microservice Documentation

> **Version:** 0.0.1-SNAPSHOT  
> **Port:** 8081  
> **Base URL:** `http://localhost:8081`

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

*Last Updated: April 20, 2026*

