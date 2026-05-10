package com.onlineshopping.api_gateway.filter;

import com.onlineshopping.api_gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Advanced Rate Limiting Filter
 *
 * Features:
 *  1. Sliding Window Counter  — eliminates burst-at-window-boundary issue
 *  2. Lua Script              — atomic increment + expire (no race conditions)
 *  3. Per-API Limits          — different limits for login, search, payment etc.
 *  4. User-Based Limiting     — uses JWT userId when available, falls back to IP
 *  5. Distributed             — all gateway instances share same Redis counters
 *
 * Sliding Window Logic (vs Fixed Window):
 *  Fixed:   resets every 60s → 100 req at :59 + 100 req at :01 = 200 req in 2s ✗
 *  Sliding: counts requests in the LAST 60s from NOW → always accurate ✓
 */
@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    // Default limit — overridden by per-path config below
    @Value("${rate.limit.requests-per-minute:100}")
    private int defaultRequestsPerMinute;

    /**
     * Per-API rate limits (requests per minute).
     * Key = path prefix, Value = max requests/min.
     *
     * Why different limits?
     *  /api/auth/login   → brute-force protection (low limit)
     *  /api/orders       → payment protection (medium limit)
     *  /api/products     → read-heavy, can be higher
     */
    private static final Map<String, Integer> PATH_LIMITS = Map.of(
            "/api/auth/login",    5,    // Brute-force protection
            "/api/auth/register", 10,   // Prevent fake account creation
            "/api/orders",        20,   // Payment / order protection
            "/api/products",      150,  // Read-heavy, higher limit
            "/api/users",         50    // Profile operations
    );

    /**
     * Lua Script for Sliding Window Rate Limiting.
     *
     * Why Lua?
     *  - Executes atomically on Redis server — no race conditions
     *  - ZADD + ZREMRANGEBYSCORE + ZCARD + EXPIRE in ONE atomic operation
     *  - If Redis crashes mid-way, all or nothing — key never gets stuck without TTL
     *
     * Script explanation:
     *  KEYS[1]  = rate limit Redis key  (e.g. "rate_limit:userId:123:/api/orders")
     *  ARGV[1]  = current timestamp in milliseconds
     *  ARGV[2]  = window start = now - 60000ms
     *  ARGV[3]  = max allowed requests
     *  ARGV[4]  = window duration in seconds (for EXPIRE)
     *
     *  1. ZREMRANGEBYSCORE — removes all entries older than window (sliding part)
     *  2. ZADD             — adds current request timestamp as member+score
     *  3. ZCARD            — counts total requests in sliding window
     *  4. EXPIRE           — sets TTL so key auto-cleans from Redis
     *  Returns: current count
     */
    private static final String SLIDING_WINDOW_LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local now = tonumber(ARGV[1]) " +
            "local window_start = tonumber(ARGV[2]) " +
            "local max_requests = tonumber(ARGV[3]) " +
            "local window_seconds = tonumber(ARGV[4]) " +
            // Remove requests outside the sliding window
            "redis.call('ZREMRANGEBYSCORE', key, 0, window_start) " +
            // Add current request (score = timestamp, member = timestamp as unique string)
            "redis.call('ZADD', key, now, tostring(now) .. '-' .. math.random(100000)) " +
            // Count requests in current window
            "local count = redis.call('ZCARD', key) " +
            // Set TTL so key doesn't stay in Redis forever
            "redis.call('EXPIRE', key, window_seconds) " +
            "return count";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // Determine limit for this specific path
        int limit = resolveLimit(path);

        // Determine identifier: prefer userId from JWT, fall back to IP
        return resolveIdentifier(request)
                .flatMap(identifier -> {
                    String rateLimitKey = "rate_limit:" + identifier + ":" + resolvePathBucket(path);
                    return checkRateLimit(rateLimitKey, limit, exchange, chain);
                });
    }

    /**
     * Execute Lua script reactively via Redis EVAL.
     * Returns the current sliding window request count.
     */
    private Mono<Void> checkRateLimit(String key, int limit,
                                      ServerWebExchange exchange, GatewayFilterChain chain) {
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L; // 60 seconds ago
        long windowSeconds = 70L;          // TTL slightly longer than window

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(SLIDING_WINDOW_LUA_SCRIPT);
        script.setResultType(Long.class);

        return redisTemplate.execute(script,
                        List.of(key),
                        List.of(
                                String.valueOf(now),
                                String.valueOf(windowStart),
                                String.valueOf(limit),
                                String.valueOf(windowSeconds)
                        ))
                .next()
                .map(result -> {
                    // Handle potential type mismatch from ReactiveRedisTemplate<String, String>
                    // Lua script returns integer, but template may deserialize as String
                    if (result instanceof Number) {
                        return ((Number) result).longValue();
                    }
                    try {
                        return Long.parseLong(String.valueOf(result));
                    } catch (Exception e) {
                        return 0L;
                    }
                })
                .defaultIfEmpty(0L)
                .onErrorReturn(0L)
                .flatMap(count -> {
                    // Add rate limit headers so clients know their status
                    exchange.getResponse().getHeaders()
                            .add("X-RateLimit-Limit", String.valueOf(limit));
                    exchange.getResponse().getHeaders()
                            .add("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));

                    if (count > limit) {
                        // Rate limit exceeded
                        exchange.getResponse().getHeaders()
                                .add("X-RateLimit-Retry-After", "60");
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        return exchange.getResponse().setComplete();
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(e -> {
                    // Catch ANY error (including inside flatMap) — fail open
                    return chain.filter(exchange);
                });
    }

    /**
     * Resolve identifier for rate limiting.
     *
     * Priority:
     *  1. JWT userId  — most accurate, not affected by NAT/shared IP
     *  2. Client IP   — fallback for unauthenticated requests (login, register)
     *
     * Why userId > IP?
     *  Multiple users behind corporate NAT share same IP.
     *  Using IP would rate-limit an entire office for one user's abuse.
     */
    private Mono<String> resolveIdentifier(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Claims claims = jwtUtil.extractAllClaims(token);
                String userId = claims.get("userId", String.class);
                if (userId == null) {
                    // Fallback: try subject
                    userId = claims.getSubject();
                }
                if (userId != null) {
                    return Mono.just("user:" + userId);
                }
            } catch (Exception ignored) {
                // Invalid token → fall through to IP-based limiting
            }
        }
        return Mono.just("ip:" + getClientIp(request));
    }

    /**
     * Map request path to a rate-limit bucket name.
     * Groups similar paths so /api/orders/1 and /api/orders/2 share the same counter.
     */
    private String resolvePathBucket(String path) {
        for (String prefix : PATH_LIMITS.keySet()) {
            if (path.startsWith(prefix)) {
                // Use the prefix as bucket key (replace / with _)
                return prefix.replace("/", "_");
            }
        }
        return "default";
    }

    /**
     * Get the rate limit for a specific path.
     * Checks path prefixes in order — first match wins.
     */
    private int resolveLimit(String path) {
        for (Map.Entry<String, Integer> entry : PATH_LIMITS.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return defaultRequestsPerMinute;
    }

    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    @Override
    public int getOrder() {
        return -2; // Run before JWT Auth Filter
    }
}

