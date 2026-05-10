# Redis Caching — Interview Summary (Order Service)

---

## 1) What is Redis Caching?

Redis is an **in-memory key-value data store** used as a **cache layer** between the application and the database. Instead of hitting the database on every request, frequently accessed data is stored in Redis and served from memory — which is **10–100x faster** than a database query.

### Why Use Caching?
| Without Cache | With Cache |
|---------------|------------|
| Every `GET /order/{id}` hits MongoDB | First call hits MongoDB, subsequent calls served from Redis |
| High DB load under traffic | DB load reduced dramatically |
| ~5-20ms per DB query | ~0.1-1ms from Redis |
| Database becomes bottleneck | Cache absorbs read traffic |

---

## 2) How Redis Caching Works in This Project

### Architecture Flow

```
Client → OrderController → OrderServiceImpl → Redis Cache → MongoDB
                                                  ↑
                                          Cache HIT? Return
                                          Cache MISS? Query DB → Store in Redis → Return
```

### Step-by-Step Flow for `GET /order/{id}`:

1. **Request arrives** at `OrderController.getOrderById(id)`
2. Spring's **cache proxy** intercepts the call (because of `@Cacheable`)
3. It checks Redis for key: `orders::{id}`
4. **Cache HIT** → Returns cached `OrderResponse` directly (DB is never touched)
5. **Cache MISS** → Executes the actual method:
   - Queries MongoDB for the order
   - Maps entity to `OrderResponse`
   - **Stores result in Redis** with key `orders::{id}` and TTL of 5 minutes
   - Returns response
6. Next call within 5 minutes → served from Redis (Cache HIT)

---

## 3) Implementation in This Project

### 3.1) Dependencies (pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

### 3.2) Configuration Properties

```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms
```

### 3.3) RedisConfig.java — Cache Manager Configuration

```java
@Configuration
@EnableCaching  // ← Enables Spring's annotation-driven caching
public class RedisConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))       // ← Cache entries expire after 5 minutes
                .disableCachingNullValues();            // ← Don't cache null (avoid caching "not found")

        return RedisCacheManager.builder(factory)
                .cacheDefaults(config)                 // ← Apply config to all caches
                .build();
    }
}
```

**Interview Explanation:**
> "I created a `RedisConfig` class annotated with `@Configuration` and `@EnableCaching`. Inside, I defined a `RedisCacheManager` bean that uses `RedisCacheConfiguration` with a 5-minute TTL. I also disabled caching of null values to prevent caching failed lookups."

### 3.4) Service Layer — Using Cache Annotations

```java
@Override
@Cacheable(value = "orders", key = "#id")
public OrderResponse getOrderById(String id) {
    Order order = orderRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("Order Not found"));
    return mapToResponse(order);
}

@Override
@CacheEvict(value = "orders", key = "#id")
public void updateOrderStatus(String id, String status) {
    Order order = orderRepository.findById(id)
        .orElseThrow(() -> new RuntimeException("Order Not found"));
    order.setStatus(status);
    orderRepository.save(order);
}
```

---

## 4) Spring Cache Annotations Explained

### `@EnableCaching`
- Placed on a `@Configuration` class
- Activates Spring's **AOP-based cache proxy** (creates proxies around `@Cacheable` methods)
- Without this, all cache annotations are ignored

### `@Cacheable(value = "orders", key = "#id")`
| Attribute | Value | Meaning |
|-----------|-------|---------|
| `value` | `"orders"` | Name of the cache (Redis key prefix) |
| `key` | `"#id"` | SpEL expression — uses method parameter `id` as cache key |

**Behavior:**
- **Before method executes:** Check if `orders::{id}` exists in Redis
- **If YES (HIT):** Return cached value, **skip method entirely**
- **If NO (MISS):** Execute method, cache the return value, then return it

**Redis Key Format:** `orders::6651abc123def456`

### `@CacheEvict(value = "orders", key = "#id")`
| Attribute | Value | Meaning |
|-----------|-------|---------|
| `value` | `"orders"` | Cache name to evict from |
| `key` | `"#id"` | Key to remove |

**Behavior:**
- **After method executes:** Removes `orders::{id}` from Redis
- Ensures next `getOrderById(id)` fetches **fresh data** from DB

**Why evict on update?**
> If we update the order status in DB but don't evict the cache, `getOrderById` would return **stale data** (old status) until the TTL expires.

### `@CachePut(value = "orders", key = "#id")` (Not used here, but know for interview)
- **Always executes** the method
- **Updates** the cache with the new return value
- Use when you want to refresh the cache with updated data

### Comparison Table (Interview Gold ⭐)

| Annotation | Checks Cache First? | Executes Method? | Updates Cache? | Removes Cache? |
|------------|---------------------|-------------------|----------------|----------------|
| `@Cacheable` | ✅ Yes | Only on MISS | ✅ On MISS | ❌ No |
| `@CacheEvict` | ❌ No | ✅ Always | ❌ No | ✅ Yes |
| `@CachePut` | ❌ No | ✅ Always | ✅ Always | ❌ No |

---

## 5) Cache Strategy Used — Cache-Aside (Lazy Loading)

This project uses the **Cache-Aside** pattern:

```
READ:
  1. Check cache → HIT? Return cached data
  2. MISS? Query database
  3. Store result in cache
  4. Return result

WRITE/UPDATE:
  1. Update database
  2. Evict (invalidate) cache entry
  3. Next read will repopulate cache from DB
```

### Why Cache-Aside?
- Simple to implement with Spring annotations
- Only caches data that is actually requested (lazy)
- Database is the source of truth

### Alternative Patterns (Know for interview):
| Pattern | Description | Pros | Cons |
|---------|-------------|------|------|
| **Cache-Aside** | App manages cache manually | Simple, lazy loading | Cache miss penalty |
| **Read-Through** | Cache loads from DB on miss automatically | Transparent to app | Needs cache provider support |
| **Write-Through** | Write to cache + DB simultaneously | Always consistent | Write latency |
| **Write-Behind** | Write to cache, async write to DB | Fast writes | Data loss risk |
| **Write-Around** | Write directly to DB, skip cache | No write overhead | Cache miss on read after write |

---

## 6) TTL (Time To Live) — Why 5 Minutes?

```java
.entryTtl(Duration.ofMinutes(5))
```

| Aspect | Detail |
|--------|--------|
| **What it does** | Every cached entry auto-expires after 5 minutes |
| **Why needed** | Prevents serving stale data indefinitely |
| **Trade-off** | Short TTL = more DB hits, Long TTL = more stale data |
| **5 min rationale** | Orders don't change frequently; 5 min is a good balance |

### What happens when TTL expires?
1. Redis automatically deletes the key
2. Next `getOrderById(id)` call → Cache MISS → fetches from DB → re-caches

---

## 7) Why `disableCachingNullValues()`?

```java
.disableCachingNullValues()
```

**Problem without this:**
- If `getOrderById("invalid-id")` throws exception and returns null
- Spring caches `null` in Redis
- Every subsequent call returns `null` from cache — even if the order is created later
- This is called **cache poisoning** or **negative caching problem**

**With `disableCachingNullValues()`:**
- `null` results are never stored in Redis
- Every request for non-existent data goes to DB (correct behavior)

---

## 8) How Spring Caching Works Under the Hood (AOP Proxy)

```
Controller calls orderService.getOrderById("abc")
        ↓
Spring AOP Proxy intercepts the call (NOT the actual method)
        ↓
Proxy checks: Is @Cacheable present?
        ↓ YES
Proxy looks up Redis: key = "orders::abc"
        ↓
FOUND → Return cached value (method NEVER executes)
NOT FOUND → Call actual method → Cache result → Return
```

**Important Interview Point:**
> `@Cacheable` only works when called from **outside the class**. Internal method calls (this.getOrderById()) bypass the proxy and skip caching. This is a common Spring AOP limitation.

```java
// ❌ WRONG — Cache will NOT work (internal call bypasses proxy)
public void someMethod() {
    this.getOrderById("abc");  // Calls method directly, not through proxy
}

// ✅ CORRECT — Cache works (external call goes through proxy)
// Called from OrderController → orderService.getOrderById("abc")
```

---

## 9) Redis Data Storage Format

### What gets stored in Redis:

| Redis Key | Value | TTL |
|-----------|-------|-----|
| `orders::6651abc123def456` | Serialized `OrderResponse` (JSON/JDK) | 5 min |

### Default Serialization:
- Spring uses **JDK serialization** by default (binary format)
- Can be changed to **JSON** with `GenericJackson2JsonRedisSerializer` for readability

### To verify in Redis CLI:
```bash
redis-cli
> KEYS orders::*
> GET orders::6651abc123def456
> TTL orders::6651abc123def456    # Returns remaining seconds
```

---

## 10) Common Interview Questions & Answers

### Q1: "Why did you use Redis for caching?"
> "Redis is an in-memory data store with sub-millisecond latency. In our order-service, the `getOrderById` endpoint was frequently called. By caching responses in Redis with a 5-minute TTL, we reduced MongoDB load and improved response times from ~10ms to under 1ms for cache hits."

### Q2: "How do you handle cache invalidation?"
> "I use `@CacheEvict` on the `updateOrderStatus` method. When an order status is updated in the database, the corresponding cache entry is evicted. The next read will fetch fresh data from MongoDB and re-populate the cache. This ensures data consistency."

### Q3: "What happens if Redis goes down?"
> "The application still works. Spring's caching abstraction gracefully handles Redis failures — the `@Cacheable` annotation simply falls back to executing the actual method (querying MongoDB directly). There's a performance degradation but no data loss or application crash."

### Q4: "Why not use `@CachePut` instead of `@CacheEvict`?"
> "I used `@CacheEvict` because `updateOrderStatus` returns `void`, not the updated order. `@CachePut` requires the method to return the value to cache. Also, `@CacheEvict` is simpler — just remove stale data and let the next read repopulate it."

### Q5: "What's the difference between `@Cacheable` and `@CachePut`?"
> "`@Cacheable` checks the cache first and skips method execution on a hit. `@CachePut` always executes the method and updates the cache with the result. Use `@Cacheable` for reads, `@CachePut` for updating cache after writes."

### Q6: "Why 5-minute TTL? How did you decide?"
> "It's a trade-off between freshness and performance. Orders are read-heavy but status updates are infrequent. 5 minutes provides good cache hit rates while limiting staleness. In production, this would be tuned based on monitoring cache hit/miss ratios."

### Q7: "How does `@Cacheable` work internally?"
> "Spring creates an AOP proxy around the bean. When a `@Cacheable` method is called from outside the class, the proxy intercepts it, generates a cache key from the annotation's `value` and `key` attributes, checks Redis, and either returns the cached result or executes the method and caches the return value."

### Q8: "What is cache stampede and how do you prevent it?"
> "Cache stampede (thundering herd) happens when a popular cache entry expires and many concurrent requests all hit the database simultaneously to repopulate it. Solutions include: `@Cacheable(sync = true)` (only one thread fetches, others wait), pre-warming the cache, or using lock-based approaches."

### Q9: "Why `disableCachingNullValues()`?"
> "To prevent negative caching. If a request for a non-existent order caches `null`, subsequent requests would get `null` from cache even if the order is created later. Disabling null caching ensures every miss goes to the database."

### Q10: "Can you explain Cache-Aside pattern?"
> "In Cache-Aside, the application is responsible for reading from and writing to the cache. On reads: check cache first, if miss then query DB and populate cache. On writes: update DB first, then invalidate cache. The cache is not aware of the database — the application orchestrates everything. That's exactly what `@Cacheable` and `@CacheEvict` do."

---

## 11) Quick Reference Card (Revision)

```
┌─────────────────────────────────────────────────┐
│              REDIS CACHING CHEAT SHEET          │
├─────────────────────────────────────────────────┤
│ Dependencies: spring-boot-starter-data-redis    │
│               spring-boot-starter-cache         │
│                                                 │
│ Enable:    @EnableCaching on @Configuration     │
│                                                 │
│ Config:    RedisCacheManager bean               │
│            - entryTtl(Duration.ofMinutes(5))    │
│            - disableCachingNullValues()          │
│                                                 │
│ Properties:                                     │
│   spring.data.redis.host=localhost              │
│   spring.data.redis.port=6379                   │
│                                                 │
│ Annotations:                                    │
│   @Cacheable(value, key) → Read cache           │
│   @CacheEvict(value, key) → Remove from cache   │
│   @CachePut(value, key) → Update cache          │
│                                                 │
│ Pattern: Cache-Aside (Lazy Loading)             │
│ Serialization: JDK (default) or JSON            │
│ Key Format: {cacheName}::{keyValue}             │
│                                                 │
│ ⚠️ AOP Proxy: Only works on external calls     │
│ ⚠️ Null values: Disable to avoid poisoning     │
│ ⚠️ TTL: Balance freshness vs performance       │
└─────────────────────────────────────────────────┘
```

---

