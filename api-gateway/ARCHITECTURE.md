# Online Shopping Microservices - Architecture Design

> **Last Updated:** June 25, 2026  
> **Version:** 3.0 - Complete Integration Guide (with Spring AI)

---

## 📐 Complete Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                      CLIENT LAYER                                        │
│                    (Web Browser / Mobile App / Postman / curl)                          │
└───────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │ HTTP Request
                                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           API GATEWAY (Spring Cloud Gateway)                            │
│                                     Port: 8080                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐                    │
│  │ Rate Limiting    │ → │ JWT Auth Filter  │ → │ Redis Cache      │                    │
│  │ Filter           │   │ • Validate Token │   │ Filter           │                    │
│  │ • 100 req/min    │   │ • Check Blacklist│   │ • Cache GET      │                    │
│  │ • Per IP         │   │ • Extract Claims │   │ • 5min TTL       │                    │
│  └──────────────────┘   │ • Forward Headers│   └──────────────────┘                    │
│                         │   X-User-Id      │                                            │
│                         │   X-Username     │                                            │
│                         │   X-User-Role    │                                            │
│                         └──────────────────┘                                            │
│                                    │                                                    │
│                    ┌───────────────┼───────────────┬───────────────┐                   │
│                    │               │               │               │                   │
│              /api/auth/**    /api/users/**  /api/products/** /api/orders/**            │
│                    │               │               │               │                   │
│              ┌─────┴─────┐  ┌─────┴─────┐  ┌─────┴─────┐  ┌─────┴─────┐              │
│              │Route Config│  │Route Config│  │Route Config│  │Route Config│              │
│              │ + Load     │  │ + Load     │  │ + Load     │  │ + Load     │              │
│              │ Balancer   │  │ Balancer   │  │ Balancer   │  │ Balancer   │              │
│              └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘              │
└────────────────────┼───────────────┼───────────────┼───────────────┼────────────────────┘
                     │               │               │               │
           lb://USER-SERVICE  lb://USER-SERVICE  lb://PRODUCT-SERVICE  lb://ORDER-SERVICE
                     │               │               │               │
                     └───────┬───────┘               │               │
                             │                       │               │
                             ▼                       ▼               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                             EUREKA SERVER (Service Registry)                            │
│                                     Port: 8761                                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │
│   │ API-GATEWAY │    │USER-SERVICE │    │PRODUCT-     │    │ORDER-SERVICE│            │
│   │ :8080       │    │ :8081       │    │SERVICE :8082│    │ :8083       │            │
│   └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘            │
│                                                                                         │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐                               │
│   │INVENTORY-   │    │NOTIFICATION-│    │ AI-SERVICE  │                               │
│   │SERVICE :8084│    │SERVICE :8085│    │ :8086       │                               │
│   └─────────────┘    └─────────────┘    └─────────────┘                               │
│                                                                                         │
│   Heartbeat ♡ every 30s | Instance Health Check | Load Balancing Info                 │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                            │
              ┌─────────────────────────────┼─────────────────────────────┐
              │                             │                             │
              ▼                             ▼                             ▼
┌──────────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│    USER SERVICE      │     │   PRODUCT SERVICE    │     │    ORDER SERVICE     │
│      Port: 8081      │     │      Port: 8082      │     │      Port: 8083      │
│      MySQL           │     │      MySQL           │     │      MongoDB         │
├──────────────────────┤     ├──────────────────────┤     ├──────────────────────┤
│ POST /api/auth/      │     │ GET  /products       │     │ POST /order/newOrder │
│      register        │     │ GET  /products/{id}  │     │ GET  /order/{id}     │
│ POST /api/auth/login │     │ POST /products       │     │ GET  /order/user/    │
│ POST /api/auth/logout│     │ PUT  /products/{id}  │     │      {userId}        │
│ GET  /api/auth/user/ │     │ DELETE /products/{id}│     │ PUT  /order/{id}/    │
│      {id}            │     │ GET  /products/search│     │     status           │
│ GET  /api/auth/user/ │     │                      │     │                      │
│      profile         │     │                      │     │                      │
├──────────────────────┤     ├──────────────────────┤     ├──────────────────────┤
│ • JWT Token Gen      │     │ • Product CRUD       │     │ • Order Management   │
│ • Password Encrypt   │     │ • Category Mgmt      │     │ • Kafka Producer     │
│ • Token Blacklist    │     │ • Stock Check        │     │ • User/Product Valid │
└──────────────────────┘     └──────────────────────┘     └──────────────────────┘
           │                            │                            │
           │                            │                   ┌────────┴────────┐
           │                            │                   │                 │
           ▼                            ▼                   ▼                 ▼
┌──────────────────────┐     ┌──────────────────────┐  ┌─────────┐    ┌─────────┐
│       MySQL          │     │       MongoDB        │  │  Kafka  │    │ Feign   │
│                      │     │                      │  │         │    │ Clients │
├──────────────────────┤     ├──────────────────────┤  ├─────────┤    ├─────────┤
│ • user_db    (8081)  │     │ • orderdb    (8083)  │  │ Topic:  │    │→ User   │
│ • product_db (8082)  │     │                      │  │ order-  │    │  Service│
│                      │     │                      │  │ events  │    │→ Product│
└──────────────────────┘     └──────────────────────┘  └─────────┘    │  Service│
                                                                       └─────────┘
                                            │
                                            ▼
              ┌───────────────────────────────────────────────────────────┐
              │                        REDIS                              │
              │                      Port: 6379                           │
              ├───────────────────────────────────────────────────────────┤
              │ API Gateway:                                              │
              │ • Response Cache        cache:*         (5 min TTL)      │
              │ • Token Blacklist       token_blacklist:* (24h TTL)      │
              │ • Rate Limiting         rate_limit:*    (1 min TTL)      │
              │                                                           │
              │ User Service (SHOULD ADD):                               │
              │ • Token Blacklist       blacklist:*     (token TTL)      │
              │ • User Cache            user:*          (10 min TTL)     │
              │                                                           │
              │ Product Service (SHOULD ADD):                            │
              │ • Product Cache         product:*       (5 min TTL)      │
              │ • Category Cache        category:*      (30 min TTL)     │
              └───────────────────────────────────────────────────────────┘
```

---

## ✅ Current Implementation Status

### API Gateway (Port: 8080) - **IMPLEMENTED**
| Component | Status | File |
|-----------|--------|------|
| JWT Auth Filter | ✅ | `JwtAuthFilter.java` |
| Token Blacklist Check | ✅ | `TokenBlacklistService.java` |
| Redis Cache Filter | ✅ | `RedisCacheFilter.java` |
| Rate Limiting Filter | ✅ | `RateLimitingFilter.java` |
| Eureka Discovery Client | ✅ | `application.properties` |
| Route Configuration | ✅ | `application.properties` |
| Load Balancing (lb://) | ✅ | Automatic with Eureka |
| CORS Configuration | ✅ | `application.properties` |
| User Header Forwarding | ✅ | `X-User-Id`, `X-Username`, `X-User-Role` |

### User Service (Port: 8081)
| Component | Status | Notes |
|-----------|--------|-------|
| Auth Endpoints | ✅ | register, login, logout |
| JWT Generation | ✅ | Using JJWT 0.12.6 |
| Token Blacklist | ⚠️ | **In-Memory** - needs Redis |
| User CRUD | ✅ | Profile endpoints |
| Eureka Registration | ✅ | `UserService` |
| Redis Cache | ❌ | **NOT IMPLEMENTED** |

### Product Service (Port: 8082)
| Component | Status | Notes |
|-----------|--------|-------|
| Product CRUD | ✅ | All operations |
| Category Management | ✅ | With foreign key |
| GatewayAuthFilter | ✅ | Reads `X-User-Role` |
| Eureka Registration | ✅ | `PRODUCT-SERVICE` |
| Redis Cache | ❌ | **NOT IMPLEMENTED** |
| Role-based Access | ✅ | Admin for CUD |

### Order Service (Port: 8083)
| Component | Status | Notes |
|-----------|--------|-------|
| Order CRUD | ✅ | All operations |
| MongoDB | ✅ | `orderdb` |
| Kafka Producer | ✅ | `order-events` topic |
| Feign - UserClient | ✅ | Validates user |
| Feign - ProductClient | ✅ | Validates product |
| SecurityFilterChain | ❌ | **NOT CONFIGURED** |
| Redis Cache | ❌ | **NOT IMPLEMENTED** |

---

## 📦 1. CACHE MANAGEMENT - What to Cache in Each Service

### Current Gateway Caching ✅
```
API Gateway → Redis Cache Filter
├── Caches: ALL GET responses
├── Key: cache:{path}:{query}
├── TTL: 5 minutes
└── Headers: X-Cache: HIT/MISS
```

### ❌ Missing Cache Implementations by Service

#### User Service - Recommended Cache Strategy
```java
// Cache Keys & TTL
user:{id}                    → 10 min   // User profile by ID
user:username:{username}     → 10 min   // User by username  
blacklist:{token}            → token_expiry  // CRITICAL: Replace InMemoryTokenBlacklistService

// ⚠️ CRITICAL: Currently uses InMemoryTokenBlacklistService
// Must migrate to Redis for multi-instance deployment

// Add to user-service/application.properties:
spring.data.redis.host=localhost
spring.data.redis.port=6379

// Create RedisTokenBlacklistService.java:
@Service
public class RedisTokenBlacklistService implements TokenBlacklistService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    public void blacklistToken(String token, Date expiresAt) {
        long ttl = expiresAt.getTime() - System.currentTimeMillis();
        if (ttl > 0) {
            redisTemplate.opsForValue().set(
                "blacklist:" + token, "1", ttl, TimeUnit.MILLISECONDS);
        }
    }
    
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("blacklist:" + token));
    }
}
```

#### Product Service - Recommended Cache Strategy  
```java
// Cache Keys & TTL
product:{id}                 → 5 min    // Single product
products:page:{page}:{size}  → 3 min    // Paginated list
products:search:{query}      → 2 min    // Search results
category:all                 → 30 min   // Category list (rarely changes)

// Add @Cacheable to ProductServiceImpl.java:
@Cacheable(value = "products", key = "#id")
public ProductResponse getProductById(Long id) { ... }

@Cacheable(value = "categories", key = "'all'")
public List<Category> getAllCategories() { ... }

@CacheEvict(value = {"products", "productPages"}, allEntries = true)
public void createProduct(CreateProduct dto) { ... }
```

#### Order Service - Recommended Cache Strategy
```java
// Cache Keys & TTL  
order:{id}                   → 5 min    // Single order
orders:user:{userId}         → 3 min    // User's orders

// ⚠️ Cache carefully - orders change status frequently
// Consider caching only DELIVERED orders longer

@Cacheable(value = "orders", key = "#id", condition = "#result.status == 'DELIVERED'")
public OrderResponse getOrderById(String id) { ... }
```

### Cache Invalidation Strategy
```
┌─────────────────────────────────────────────────────────┐
│                 CACHE INVALIDATION FLOW                 │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Product Updated/Deleted                                │
│  └── Evict: product:{id}, products:*, category:*       │
│                                                         │
│  Order Status Changed                                   │
│  └── Evict: order:{id}, orders:user:{userId}           │
│                                                         │
│  User Profile Updated                                   │
│  └── Evict: user:{id}, user:username:{username}        │
│                                                         │
│  User Logout                                            │
│  └── Add: blacklist:{token} (Redis TTL = token TTL)    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 🔐 2. SECURITY MANAGEMENT - Centralized JWT Validation

### Current Architecture: Gateway-Based Security ✅

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                        SECURITY FLOW (CORRECT)                                │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  Client Request                                                               │
│       │                                                                       │
│       ▼                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                    API GATEWAY (Port 8080)                              │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │ │
│  │  │              JwtAuthFilter.java                                  │   │ │
│  │  │  1. Extract token from Authorization: Bearer <token>            │   │ │
│  │  │  2. Validate token signature (same jwt.secret)                  │   │ │
│  │  │  3. Check token not in Redis blacklist                          │   │ │
│  │  │  4. Extract claims: userId, username, role                      │   │ │
│  │  │  5. Forward as HTTP headers to downstream service               │   │ │
│  │  └─────────────────────────────────────────────────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│       │                                                                       │
│       │ Headers:                                                              │
│       │   X-User-Id: 123                                                      │
│       │   X-Username: john_doe                                                │
│       │   X-User-Role: ADMIN                                                  │
│       ▼                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │               DOWNSTREAM SERVICES (8081, 8082, 8083)                    │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐   │ │
│  │  │         GatewayAuthFilter.java (Product/User Service)           │   │ │
│  │  │  1. Read X-User-Id, X-Username, X-User-Role headers             │   │ │
│  │  │  2. Create Authentication object                                │   │ │
│  │  │  3. Set SecurityContext                                         │   │ │
│  │  │  4. NO JWT VALIDATION HERE - Trust Gateway                      │   │ │
│  │  └─────────────────────────────────────────────────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

### JWT Secret Configuration - MUST BE IDENTICAL

```properties
# ⚠️ ALL SERVICES MUST USE THE SAME SECRET

# api-gateway/application.properties
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==

# user-service/application.properties  
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==

# product-service/application.properties (kept for backward compatibility)
jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZA==  # ⚠️ DIFFERENT - Should match!

# order-service/application.properties
jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZA==  # ⚠️ DIFFERENT - Should match!
```

### ⚠️ ISSUES FOUND

| Service | Issue | Fix Required |
|---------|-------|--------------|
| **Order Service** | No `SecurityFilterChain` configured | Add GatewayAuthFilter |
| **Order Service** | Has `JwtUtil` but not using headers | Read X-User headers |
| **Product Service** | Different `jwt.secret` | Match with Gateway |
| **User Service** | InMemory token blacklist | Use Redis |

### Required: GatewayAuthFilter for Order Service

```java
// order-service/security/GatewayAuthFilter.java
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) 
            throws ServletException, IOException {
        
        String userId = request.getHeader("X-User-Id");
        String username = request.getHeader("X-Username");
        String role = request.getHeader("X-User-Role");
        
        if (username != null && !username.isEmpty()) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (role != null && !role.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            
            UsernamePasswordAuthenticationToken auth = 
                new UsernamePasswordAuthenticationToken(username, null, authorities);
            auth.setDetails(Map.of("userId", userId != null ? userId : ""));
            
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        chain.doFilter(request, response);
    }
}
```

### Required: SecurityConfig for Order Service

```java
// order-service/security/SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private GatewayAuthFilter gatewayAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

---

## 🔗 3. INTER-SERVICE COMMUNICATION - Feign Clients

### Current Implementation in Order Service ✅

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ORDER SERVICE FEIGN CLIENTS                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  OrderServiceImpl.placeOrder(NewOrder)                                      │
│       │                                                                     │
│       ├──► UserClient.getUserById(userId)                                   │
│       │         │                                                           │
│       │         └──► GET user-service/api/auth/user/{id}                   │
│       │               Returns: { id, name, email }                          │
│       │                                                                     │
│       └──► ProductClient.getProductById(productId)  [for each item]        │
│                 │                                                           │
│                 └──► GET product-service/api/products/{id}                 │
│                       Returns: { id, name, price, categoryName }            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Feign Client Definitions

```java
// order-service/client/UserClient.java
@FeignClient(name = "user-service")
public interface UserClient {
    @GetMapping("/api/auth/user/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);
}

// order-service/client/ProductClient.java  
@FeignClient(name = "product-service")
public interface ProductClient {
    @GetMapping("/api/products/{id}")
    ProductResponse getProductById(@PathVariable("id") Long id);
}
```

### Expected Response DTOs

```java
// UserResponse - Expected from User Service
{
    "id": 101,
    "name": "John Doe",     // ⚠️ User Service returns "username", not "name"
    "email": "john@example.com"
}

// ProductResponse - Expected from Product Service  
{
    "id": 1,
    "name": "Laptop",
    "price": 999.99,
    "categoryName": "Electronics"
}
```

### ⚠️ MISMATCH FOUND: UserResponse Field Names

```
Order Service expects:     User Service returns:
─────────────────────     ────────────────────
{ "name": ... }           { "username": ... }
```

**Fix Option 1:** Update User Service to return `name` field
```java
// user-service UserResponse.java
public class UserResponse {
    private Long id;
    private String name;      // Map from username
    private String email;
}
```

**Fix Option 2:** Update Order Service DTO to match
```java
// order-service UserResponse.java  
public class UserResponse {
    private Long id;
    private String username;  // Match User Service
    private String email;
}
```

### 🆕 ADDITIONAL FEIGN CLIENTS TO ADD

#### A) Product Service → Order Service (Stock Reservation)

```java
// product-service/client/OrderClient.java
@FeignClient(name = "order-service")
public interface OrderClient {
    
    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);
    
    // Use case: Show "Last ordered" on product page
}
```

#### B) Product Service → User Service (Admin Validation)

```java
// product-service/client/UserClient.java
@FeignClient(name = "user-service")
public interface UserClient {
    
    @GetMapping("/api/auth/user/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);
    
    // Use case: Verify admin exists before product operations
}
```

#### C) User Service → Order Service (Order History)

```java
// user-service/client/OrderClient.java
@FeignClient(name = "order-service")
public interface OrderClient {
    
    @GetMapping("/order/user/{userId}")
    OrderResponse getUserOrders(@PathVariable("userId") Long userId);
    
    // Use case: Show order count on user profile
}
```

### Complete Service Communication Matrix

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                     SERVICE COMMUNICATION MATRIX                                    │
├─────────────────┬───────────────┬─────────────────┬─────────────────────────────────┤
│ FROM ↓ / TO →   │ User Service  │ Product Service │ Order Service                   │
├─────────────────┼───────────────┼─────────────────┼─────────────────────────────────┤
│ User Service    │      -        │       -         │ GET /order/user/{id}           │
│                 │               │                 │ (order history)                 │
├─────────────────┼───────────────┼─────────────────┼─────────────────────────────────┤
│ Product Service │ GET /api/auth │       -         │ GET /order/user/{id}           │
│                 │ /user/{id}    │                 │ (recently ordered)              │
├─────────────────┼───────────────┼─────────────────┼─────────────────────────────────┤
│ Order Service   │ GET /api/auth │ GET /api/       │       -                         │
│                 │ /user/{id} ✅ │ products/{id} ✅ │                                 │
├─────────────────┼───────────────┼─────────────────┼─────────────────────────────────┤
│ API Gateway     │ All routes    │ All routes      │ All routes                      │
│                 │ (lb://USER-   │ (lb://PRODUCT-  │ (lb://ORDER-                    │
│                 │  SERVICE)     │  SERVICE)       │  SERVICE)                       │
└─────────────────┴───────────────┴─────────────────┴─────────────────────────────────┘
```

### Feign Configuration with Header Propagation

```java
// shared/FeignConfig.java - Add to all services with Feign clients
@Configuration
public class FeignConfig {
    
    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            // Propagate user headers for inter-service calls
            ServletRequestAttributes attrs = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String userId = request.getHeader("X-User-Id");
                String username = request.getHeader("X-Username");
                String role = request.getHeader("X-User-Role");
                
                if (userId != null) requestTemplate.header("X-User-Id", userId);
                if (username != null) requestTemplate.header("X-Username", username);
                if (role != null) requestTemplate.header("X-User-Role", role);
            }
        };
    }
}
```

---

## ⚙️ 4. REMAINING CONFIGURATIONS

### A) JWT Secret Alignment ❌

```properties
# ⚠️ CRITICAL: All services MUST use the SAME jwt.secret

# CURRENT (INCONSISTENT):
api-gateway:     jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9y...
user-service:    jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9y...
product-service: jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox...  ← DIFFERENT!
order-service:   jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXox...  ← DIFFERENT!

# FIX: Update product-service and order-service to match
jwt.secret=dGhpc2lzYXZlcnlsb25nc2VjcmV0a2V5Zm9yand0YXV0aGVudGljYXRpb25vbmxpbmVzaG9wcGluZw==
```

### B) API Gateway Route Paths vs Service Paths ⚠️

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                    ROUTE PATH MISMATCH ANALYSIS                                │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                │
│  API Gateway Route          Service Actual Path      Status                    │
│  ─────────────────          ──────────────────       ──────                    │
│  /api/auth/**         →     /api/auth/**             ✅ Match                  │
│  /api/users/**        →     /api/auth/user/**        ⚠️ Check mapping          │
│  /api/products/**     →     /products/**             ❌ MISMATCH!              │
│  /api/orders/**       →     /order/**                ❌ MISMATCH!              │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

**Fix Required in application.properties (API Gateway):**

```properties
# Option 1: Use RewritePath filter
spring.cloud.gateway.routes[2].id=product-service
spring.cloud.gateway.routes[2].uri=lb://PRODUCT-SERVICE
spring.cloud.gateway.routes[2].predicates[0]=Path=/api/products/**
spring.cloud.gateway.routes[2].filters[0]=RewritePath=/api/products/(?<segment>.*), /products/${segment}

spring.cloud.gateway.routes[3].id=order-service
spring.cloud.gateway.routes[3].uri=lb://ORDER-SERVICE  
spring.cloud.gateway.routes[3].predicates[0]=Path=/api/orders/**
spring.cloud.gateway.routes[3].filters[0]=RewritePath=/api/orders/(?<segment>.*), /order/${segment}
```

**OR Fix in Services:** Update Product/Order Service to use `/api/products` and `/api/orders` paths.

### C) Missing Circuit Breaker (Resilience4j)

```java
// Add to order-service (already has dependency)
@CircuitBreaker(name = "productService", fallbackMethod = "productFallback")
public ProductResponse getProduct(Long id) {
    return productClient.getProductById(id);
}

public ProductResponse productFallback(Long id, Exception e) {
    return ProductResponse.builder()
        .id(id)
        .name("Product Unavailable")
        .price(0.0)
        .build();
}

// application.properties
resilience4j.circuitbreaker.instances.productService.slidingWindowSize=10
resilience4j.circuitbreaker.instances.productService.failureRateThreshold=50
resilience4j.circuitbreaker.instances.productService.waitDurationInOpenState=10000
```

### D) Missing Global Exception Handler

```java
// Add to each service: /exception/GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntime(RuntimeException e) {
        return ResponseEntity.status(500).body(
            new ErrorResponse("ERROR", e.getMessage())
        );
    }

    @ExceptionHandler(FeignException.class)  
    public ResponseEntity<ErrorResponse> handleFeign(FeignException e) {
        return ResponseEntity.status(e.status()).body(
            new ErrorResponse("SERVICE_ERROR", "Downstream service unavailable")
        );
    }
}
```

### E) Kafka Consumer (Missing)

```
Order Service publishes to: order-events
No consumer service exists yet!

Potential consumers:
├── notification-service  → Send order confirmation emails
├── inventory-service     → Reduce stock after order
└── analytics-service     → Track order metrics
```

### F) Docker Compose Configuration

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
    ports:
      - "3306:3306"
    volumes:
      - ./init-db.sql:/docker-entrypoint-initdb.d/init.sql

  mongodb:
    image: mongo:6.0
    ports:
      - "27017:27017"
    volumes:
      - mongodb_data:/data/db

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.4.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1

volumes:
  mongodb_data:
```

```sql
-- init-db.sql
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS product_db;
```

---

## 📋 SUMMARY: Action Items Checklist

### 🔴 Critical (Must Fix)
- [ ] **JWT Secret**: Align all services to use same secret
- [ ] **Order Service**: Add SecurityFilterChain + GatewayAuthFilter
- [ ] **User Service**: Replace InMemoryTokenBlacklistService with Redis
- [ ] **Route Paths**: Fix Gateway → Service path mapping

### 🟡 Important (Should Fix)  
- [ ] **Product Service**: Add Redis caching for products/categories
- [ ] **Order Service**: Add Redis caching for orders
- [ ] **Circuit Breaker**: Configure Resilience4j in Order Service
- [ ] **UserResponse DTO**: Fix name vs username field mismatch

### 🟢 Nice to Have
- [ ] **Header Propagation**: Add FeignConfig to propagate X-User headers
- [ ] **Additional Feign Clients**: Product→Order, User→Order
- [ ] **Global Exception Handler**: Add to all services
- [ ] **Kafka Consumer**: Create notification-service

---

## 🔗 Request Flow (End-to-End)

### Login Flow:
```
Client                    Gateway              User Service           Redis
   │                         │                       │                   │
   │ POST /api/auth/login    │                       │                   │
   │ ─────────────────────►  │                       │                   │
   │                         │ (Bypass JWT Filter)   │                   │
   │                         │ ──────────────────►   │                   │
   │                         │                       │ Validate Creds    │
   │                         │                       │ Generate JWT      │
   │                         │                       │ (userId, role)    │
   │                         │  ◄───────────────────  │                   │
   │  ◄───────────────────── │ { token: "..." }     │                   │
   │                         │                       │                   │
```

### Create Order Flow (with Feign Calls):
```
Client          Gateway        Order Service      User Service    Product Service
   │               │                 │                  │                │
   │ POST          │                 │                  │                │
   │ /api/orders   │                 │                  │                │
   │ {userId,      │                 │                  │                │
   │  items:[]}    │                 │                  │                │
   │ ─────────────►│                 │                  │                │
   │               │                 │                  │                │
   │               │ Validate JWT    │                  │                │
   │               │ Extract claims  │                  │                │
   │               │ Add headers     │                  │                │
   │               │ ────────────────►                  │                │
   │               │                 │                  │                │
   │               │                 │ UserClient       │                │
   │               │                 │ GET /api/auth/   │                │
   │               │                 │ user/{userId}    │                │
   │               │                 │ ─────────────────►                │
   │               │                 │ ◄─────────────────                │
   │               │                 │ {id,name,email}  │                │
   │               │                 │                  │                │
   │               │                 │ ProductClient    │                │
   │               │                 │ (for each item)  │                │
   │               │                 │ GET /products/{id}               │
   │               │                 │ ──────────────────────────────────►
   │               │                 │ ◄──────────────────────────────────
   │               │                 │ {id,name,price}  │                │
   │               │                 │                  │                │
   │               │                 │ Save to MongoDB  │                │
   │               │                 │ Publish to Kafka │                │
   │               │ ◄────────────────                  │                │
   │ ◄─────────────│ "Order created" │                  │                │
```

### Protected Request Flow (with Caching):
```
Client                    Gateway              Redis            Product Service
   │                         │                   │                       │
   │ GET /api/products       │                   │                       │
   │ Authorization: Bearer   │                   │                       │
   │ ─────────────────────►  │                   │                       │
   │                         │                   │                       │
   │                    ┌────┴────┐              │                       │
   │                    │ Rate    │              │                       │
   │                    │ Limit   │◄─ INCR ──────►                       │
   │                    │ Check   │  rate_limit: │                       │
   │                    └────┬────┘              │                       │
   │                         │                   │                       │
   │                    ┌────┴────┐              │                       │
   │                    │ JWT     │◄─ EXISTS ────►                       │
   │                    │ Auth    │ blacklist:*  │                       │
   │                    │ Filter  │              │                       │
   │                    └────┬────┘              │                       │
   │                         │                   │                       │
   │                    ┌────┴────┐              │                       │
   │                    │ Redis   │◄─ GET ───────►                       │
   │                    │ Cache   │ cache:path:* │                       │
   │                    └────┬────┘              │                       │
   │                         │                   │                       │
   │                    Cache HIT?               │                       │
   │                    ┌─YES─┘                  │                       │
   │ ◄──────────────────┤                        │                       │
   │    X-Cache: HIT    │                        │                       │
   │                    └─NO──┐                  │                       │
   │                          │                  │                       │
   │                          │ Forward with headers:                    │
   │                          │ X-User-Id, X-Username, X-User-Role      │
   │                          │ ─────────────────────────────────────────►
   │                          │                  │                       │
   │                          │ ◄─────────────────────────────────────────
   │                          │                  │                       │
   │                          │ ─── SET ─────────►                       │
   │                          │     cache:*      │                       │
   │ ◄────────────────────────│ X-Cache: MISS   │                       │
```

---

## 📝 How Downstream Services Read Headers

### Product Service - GatewayAuthFilter (IMPLEMENTED ✅)
```java
// Location: product-service/security/GatewayAuthFilter.java
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {
    
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain chain) {
        
        String userId = request.getHeader(HEADER_USER_ID);
        String username = request.getHeader(HEADER_USERNAME);
        String role = request.getHeader(HEADER_USER_ROLE);

        if (username != null && !username.isEmpty()) {
            // Convert "ADMIN" → "ROLE_ADMIN" for Spring Security
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (role != null && !role.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, null, authorities);
            
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        
        chain.doFilter(request, response);
    }
}
```

### Example: Using Headers in Controller
```java
@RestController
@RequestMapping("/products")
public class ProductController {

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")  // Works because GatewayAuthFilter sets ROLE_ADMIN
    public ResponseEntity<?> createProduct(@RequestBody CreateProduct dto) {
        return ResponseEntity.ok(productService.createProduct(dto));
    }

    @GetMapping
    public ResponseEntity<?> getProducts(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // Public endpoint - userId may be null for anonymous users
        log.info("Products accessed by user: {}", userId);
        return ResponseEntity.ok(productService.getAllProducts());
    }
}
```

### Order Service - Must Add GatewayAuthFilter ❌
```java
// MISSING: Create order-service/security/GatewayAuthFilter.java
// Copy the same pattern from Product Service
```

---

## 🔧 Required JWT Claims Structure

### User Service Must Generate JWT With:
```java
// user-service/security/JwtUtil.java
public String generateToken(User user) {
    return Jwts.builder()
            .subject(user.getUsername())
            .claim("userId", String.valueOf(user.getId()))  // REQUIRED
            .claim("role", user.getRole())                   // REQUIRED: USER or ADMIN
            .claim("email", user.getEmail())                 // Optional
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
            .signWith(getSigningKey())
            .compact();
}
```

### API Gateway Extracts and Forwards:
```java
// api-gateway/security/JwtAuthFilter.java
Claims claims = jwtUtil.extractAllClaims(token);
String userId = claims.get("userId", String.class);    // → X-User-Id
String username = claims.getSubject();                  // → X-Username
String role = claims.get("role", String.class);        // → X-User-Role

ServerHttpRequest modifiedRequest = exchange.getRequest()
    .mutate()
    .header("X-User-Id", userId != null ? userId : "")
    .header("X-Username", username != null ? username : "")
    .header("X-User-Role", role != null ? role : "USER")
    .build();
```

---

## 🚀 Step-by-Step Implementation Order

### Phase 1: Infrastructure
1. ✅ API Gateway (Done)
2. ✅ Docker Compose with MySQL + MongoDB + Redis + Kafka
3. ✅ Eureka Server

### Phase 2: Core Services
4. ✅ User Service with authentication
5. ✅ Product Service with CRUD + GatewayAuthFilter
6. ✅ Order Service with Feign clients

### Phase 3: Integration Fixes (CURRENT)
7. ⚠️ Align JWT secrets across all services
8. ⚠️ Fix route path mappings in API Gateway
9. ⚠️ Add GatewayAuthFilter to Order Service
10. ⚠️ Replace InMemory TokenBlacklist with Redis in User Service
11. ⚠️ Fix UserResponse DTO field mismatch (name vs username)

### Phase 4: Enhancements
12. Add Redis caching to Product & Order services
13. Configure Circuit Breaker (Resilience4j)
14. Add Kafka consumer service
15. Add distributed tracing (Zipkin/Sleuth)

### Phase 5: AI Integration (Spring AI)
16. ✅ Build AI Service (see `SpringAI_README.md`)
17. Add PGVector store for product embeddings
18. Implement chatbot with function calling
19. Add semantic search endpoint
20. Product Service → publish product-events to Kafka (for AI vector sync)
21. Notification Service → Feign to AI Service (smart email content)
22. Product Service → Feign to AI Service (auto-generate descriptions)

---

## 🔮 Optional Enhancements

| Enhancement | Purpose | Priority | Status |
|-------------|---------|----------|--------|
| **Spring AI Service** | Chatbot, semantic search, recommendations | HIGH | ✅ Documented |
| **Circuit Breaker** | Handle downstream failures | HIGH | Dependency exists |
| **Distributed Tracing** | Debug cross-service requests | MEDIUM | Not started |
| **Config Server** | Centralized configuration | MEDIUM | Not started |
| **Kafka Consumer** | Process order events | MEDIUM | Producer exists |
| **API Gateway Fallback** | Return cached/default on failure | HIGH | Not started |

---

## 🤖 5. SPRING AI INTEGRATION — AI-Powered Features

### AI Service (Port: 8086) — NEW MICROSERVICE

| Feature | Spring AI Component | Endpoint |
|---------|---------------------|----------|
| **Shopping Assistant Chatbot** | `ChatClient` + Function Calling | `POST /api/ai/chat` |
| **Semantic Product Search** | `EmbeddingModel` + `VectorStore` (PGVector) | `GET /api/ai/search?q=...` |
| **Product Recommendations** | `VectorStore` similarity search | `GET /api/ai/recommendations/{id}` |
| **AI Product Descriptions** | `ChatClient` prompt templates | `POST /api/ai/generate/description` |
| **Smart Notifications** | `ChatClient` content generation | `POST /api/ai/generate/notification` |
| **Streaming Responses** | `ChatClient.stream()` → SSE | `POST /api/ai/chat/stream` |

### How Function Calling Works (Spring AI)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│              SPRING AI FUNCTION CALLING — Shopping Assistant                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  User: "Is the Gaming Laptop in stock?"                                      │
│       │                                                                      │
│       ▼                                                                      │
│  ChatClient sends message to LLM (GPT-4o-mini / Llama3)                     │
│       │                                                                      │
│       │  LLM sees available functions:                                       │
│       │    - searchProducts(query, maxPrice, category)                       │
│       │    - getOrderStatus(userId)                                          │
│       │    - checkInventory(productId, productName)                          │
│       │                                                                      │
│       │  LLM decides: "I need to call checkInventory"                        │
│       │  Returns: { "name": "checkInventory", "args": {"productName":        │
│       │             "Gaming Laptop"} }                                        │
│       ▼                                                                      │
│  Spring AI intercepts → calls InventoryCheckFunction.apply()                 │
│       │                                                                      │
│       │  Function calls Product Service via Feign                            │
│       │  → GET /products?search=Gaming+Laptop                                │
│       │  → Returns: { productId: 5, stock: 42, inStock: true }               │
│       ▼                                                                      │
│  Spring AI sends function result back to LLM                                 │
│       │                                                                      │
│       │  LLM generates natural language response                             │
│       ▼                                                                      │
│  Response: "Yes! The Gaming Laptop is currently in stock with                │
│             42 units available. Would you like to place an order?"            │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Semantic Search vs Keyword Search

```
┌──────────────────────────────────────────────────────────────────────────────┐
│         KEYWORD SEARCH (existing)  vs  SEMANTIC SEARCH (Spring AI)           │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Query: "something to keep my coffee hot"                                    │
│                                                                              │
│  KEYWORD (SQL LIKE / Full-text):                                             │
│    SELECT * FROM products WHERE name LIKE '%coffee%' OR name LIKE '%hot%'    │
│    → Result: Coffee Mug (if exists)                                          │
│    → MISSES: "Insulated Travel Thermos" (no matching keywords!)              │
│                                                                              │
│  SEMANTIC (Spring AI VectorStore):                                            │
│    1. Embed query → vector [0.12, -0.45, 0.78, ...]                          │
│    2. Find nearest vectors in PGVector (cosine similarity)                    │
│    3. Results ranked by MEANING similarity:                                   │
│       → "Insulated Travel Thermos" (0.94 similarity) ✅                      │
│       → "Stainless Steel Water Bottle" (0.82 similarity)                     │
│       → "Coffee Mug - Ceramic" (0.79 similarity)                             │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Integration Points with Existing Services

```
┌──────────────────────────────────────────────────────────────────────────────┐
│              AI SERVICE INTEGRATION MAP                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Product Service ──Kafka──► AI Service (product-events topic)                │
│    • On product CRUD → re-embed in vector store                              │
│    • Keeps semantic search up-to-date                                         │
│                                                                              │
│  Product Service ──Feign──► AI Service                                       │
│    • POST /ai/generate/description                                           │
│    • Auto-generate SEO descriptions when admin skips it                      │
│                                                                              │
│  Notification Service ──Feign──► AI Service                                  │
│    • POST /ai/generate/notification                                          │
│    • AI-crafted personalized email content                                   │
│    • Fallback to static templates if AI unavailable                          │
│                                                                              │
│  AI Service ──Feign──► Product Service                                       │
│    • GET /products/{id} (for function calling)                               │
│    • GET /products/search?query=... (for chatbot)                            │
│                                                                              │
│  AI Service ──Feign──► Order Service                                         │
│    • GET /order/user/{userId} (for chatbot order status)                     │
│                                                                              │
│  AI Service ──Feign──► User Service                                          │
│    • GET /api/auth/user/{id} (for personalization)                           │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Full documentation: See `SpringAI_README.md`

---

## 📦 Quick Start Commands

```powershell
# 1. Start Infrastructure
cd "C:\Private\Spring Boot Project\Online Shopping"
docker-compose up -d

# 2. Wait for services to be ready
Start-Sleep -Seconds 30

# 3. Start Eureka Server (Terminal 1)
cd eureka-server
mvn spring-boot:run

# 4. Start User Service (Terminal 2)
cd user-service
mvn spring-boot:run

# 5. Start Product Service (Terminal 3)
cd product-service
mvn spring-boot:run

# 6. Start Order Service (Terminal 4)
cd order-service
mvn spring-boot:run

# 7. Start Inventory Service (Terminal 5)
cd inventory-service
mvn spring-boot:run

# 8. Start Notification Service (Terminal 6)
cd notification-service
mvn spring-boot:run

# 9. Start AI Service (Terminal 7) — requires OPENAI_API_KEY or Ollama
$env:OPENAI_API_KEY="sk-your-key-here"
cd ai-service
mvn spring-boot:run
# OR for local dev: mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 10. Start API Gateway (Terminal 8 — start LAST)
cd api-gateway
mvn spring-boot:run
```

---

## ✅ Current Implementation Status (May 7, 2026)

### Route Summary — FilterConfig.java

| Route ID | Path | Method | JWT? | Forwards To |
|----------|------|--------|------|-------------|
| `user-service-user-endpoints` | `/api/auth/user/**` | ANY | ✅ Yes | USER-SERVICE |
| `user-service-logout` | `/api/auth/logout` | ANY | ✅ Yes | USER-SERVICE |
| `user-service-auth` | `/api/auth/**` | ANY | ❌ No | USER-SERVICE |
| `product-service-public` | `/api/products/**` | GET | ❌ No | PRODUCT-SERVICE `/products/**` |
| `product-service-protected` | `/api/products/**` | POST/PUT/DELETE | ✅ Yes | PRODUCT-SERVICE `/products/**` |
| `order-service` | `/api/orders/**` | ANY | ✅ Yes | ORDER-SERVICE `/order/**` |
| `ai-service-public` | `/api/ai/search/**`, `/api/ai/recommendations/**` | GET | ❌ No | AI-SERVICE `/ai/**` |
| `ai-service-protected` | `/api/ai/**` | POST/PUT/DELETE | ✅ Yes | AI-SERVICE `/ai/**` |

### Rate Limits — RateLimitingFilter.java

| Endpoint | Limit | Reason |
|----------|-------|--------|
| `POST /api/auth/login` | 5/min | Brute-force protection |
| `POST /api/auth/register` | 10/min | Fake account prevention |
| `/api/orders/**` | 20/min | Payment protection |
| `/api/products/**` | 150/min | Read-heavy |
| `/api/users/**` | 50/min | Profile operations |
| Default | 100/min | All others |

### Service Status Summary

| Service | Critical Missing | Important Missing |
|---------|-----------------|-------------------|
| **API Gateway** | — Nothing | Redis pool config (done) |
| **User Service** | Redis token blacklist, JWT re-parsing in profile/update | Redis user cache |
| **Product Service** | Remove duplicate endpoint, fix JWT secret | Redis cache, Kafka producer for AI sync |
| **Order Service** | SecurityConfig, GatewayAuthFilter, fix constructor, fix Kafka injection, fix getUserOrders return type | FeignConfig, Redis cache, order status enum |
| **Inventory Service** | Full build required (see Inventory_README.md) | — |
| **Notification Service** | Full build required (see Notification_README.md) | AI-powered email content |
| **AI Service** | Full build required (see SpringAI_README.md) | — |

---

## 🧪 Complete Postman Reference

**Base URL:** `http://localhost:8080` (always via API Gateway)

### 👤 User Service

| Method | URL | Auth | Body |
|--------|-----|------|------|
| POST | `/api/auth/register` | None | `{"username":"john","email":"j@j.com","password":"pass","role":"USER"}` |
| POST | `/api/auth/login` | None | `{"username":"john","password":"pass"}` → returns `{"token":"..."}` |
| POST | `/api/auth/logout` | Bearer | — |
| GET | `/api/auth/user/{id}` | Bearer | — |
| GET | `/api/auth/user/profile` | Bearer | — |
| PUT | `/api/auth/user/profile` | Bearer | `{"email":"new@j.com","password":"newpass"}` |

### 📦 Product Service

| Method | URL | Auth | Body |
|--------|-----|------|------|
| GET | `/api/products` | None | — |
| GET | `/api/products?page=0&size=10` | None | — |
| GET | `/api/products/{id}` | None | — |
| GET | `/api/products/search?query=laptop` | None | — |
| POST | `/api/products` | Bearer ADMIN | `{"name":"Laptop","description":"...","price":999.99,"stock":50,"categoryId":1}` |
| PUT | `/api/products/{id}` | Bearer ADMIN | same as POST |
| DELETE | `/api/products/{id}` | Bearer ADMIN | — |

### 🛒 Order Service

| Method | URL | Auth | Body |
|--------|-----|------|------|
| POST | `/api/orders/newOrder` | Bearer | `{"userId":1,"items":[{"productId":1,"quantity":2}]}` |
| GET | `/api/orders/{mongoId}` | Bearer | — |
| GET | `/api/orders/user/{userId}` | Bearer | — |
| PUT | `/api/orders/{mongoId}/status?status=CONFIRMED` | Bearer | — |

### 🔍 Infrastructure

| URL | Description |
|-----|-------------|
| `http://localhost:8761` | Eureka dashboard |
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8080/actuator/gateway/routes` | Active routes |
| `http://localhost:8083/swagger-ui.html` | Order Service Swagger |

### 🤖 AI Service (Spring AI)

| Method | URL | Auth | Body |
|--------|-----|------|------|
| POST | `/api/ai/chat` | Bearer | `{"message":"Show me laptops under 50000","sessionId":"s1"}` |
| POST | `/api/ai/chat` | Bearer | `{"message":"What's my order status?","sessionId":"s1"}` |
| POST | `/api/ai/chat/stream` | Bearer | `{"message":"Recommend a phone","sessionId":"s1"}` |
| GET | `/api/ai/search?q=comfortable+running+shoes` | None | — |
| GET | `/api/ai/search?q=keep+coffee+hot&limit=5` | None | — |
| GET | `/api/ai/recommendations/1?limit=5` | None | — |
| POST | `/api/ai/generate/description` | Bearer ADMIN | `{"productName":"Earbuds","category":"Electronics","price":2999}` |
| POST | `/api/ai/generate/notification` | Bearer ADMIN | `{"status":"CONFIRMED","userName":"John","orderId":"abc","totalAmount":4999}` |

