package com.onlineshopping.api_gateway.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service for managing JWT Token Blacklist in Redis
 * Used for logout functionality - invalidated tokens are stored here
 */
@Service
public class TokenBlacklistService {

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private static final String BLACKLIST_PREFIX = "token_blacklist:";
    private static final Duration TOKEN_BLACKLIST_TTL = Duration.ofHours(24);

    /**
     * Add a token to the blacklist (called during logout)
     */
    public Mono<Boolean> blacklistToken(String token) {
        String key = BLACKLIST_PREFIX + token;
        return redisTemplate.opsForValue()
                .set(key, "blacklisted", TOKEN_BLACKLIST_TTL);
    }

    /**
     * Check if a token is blacklisted
     */
    public Mono<Boolean> isTokenBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + token;
        return redisTemplate.hasKey(key);
    }

    /**
     * Remove a token from blacklist (if needed)
     */
    public Mono<Boolean> removeFromBlacklist(String token) {
        String key = BLACKLIST_PREFIX + token;
        return redisTemplate.delete(key).map(count -> count > 0);
    }
}

