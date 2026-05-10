package com.onlineShopping.UserService.service;

import java.util.Date;

public interface TokenBlacklistService {
    void blacklistToken(String token, Date expiresAt);

    boolean isBlacklisted(String token);
}

