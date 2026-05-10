package com.onlineShopping.UserService.serviceimpl;

import com.onlineShopping.UserService.service.TokenBlacklistService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Deprecated
public class InMemoryTokenBlacklistService implements TokenBlacklistService {

    private final Map<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    @Override
    public void blacklistToken(String token, Date expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return;
        }
        blacklistedTokens.put(token, expiresAt.getTime());
    }

    @Override
    public boolean isBlacklisted(String token) {
        Long expiresAt = blacklistedTokens.get(token);
        if (expiresAt == null) {
            return false;
        }

        // Auto-evict expired blacklist entries.
        if (expiresAt <= System.currentTimeMillis()) {
            blacklistedTokens.remove(token);
            return false;
        }

        return true;
    }
}

