package com.onlineShopping.UserService.serviceimpl;

import com.onlineShopping.UserService.service.TokenBlacklistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@Primary
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