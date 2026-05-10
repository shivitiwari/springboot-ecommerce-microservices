package com.onlineShopping.UserService.security;

import com.onlineShopping.UserService.entity.User;
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
    @Value("${jwt.expiration}")
    private long expirationTime;

    /**
     * Generate JWT token with userId and role claims for API Gateway compatibility
     */
    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", String.valueOf(user.getId()))  // REQUIRED for API Gateway
                .claim("role", user.getRole())                   // REQUIRED for API Gateway (USER or ADMIN)
                .claim("email", user.getEmail())                 // Optional
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * @deprecated Use generateToken(User user) instead to include userId and role claims
     */
    @Deprecated
    public String generateToken(String username) {
        return Jwts.builder().
                subject(username).
                issuedAt(new Date()).
                expiration(new Date(System.currentTimeMillis() + expirationTime)).
                signWith(getSigningKey()).
                compact();
    }

    /**
     * Extract userId claim from token
     */
    public Long extractUserId(String token) {
        String userId = getClaims(token).get("userId", String.class);
        return userId != null ? Long.parseLong(userId) : null;
    }

    /**
     * Extract role claim from token
     */
    public String extractRole(String token) {
        return getClaims(token).get("role", String.class);
    }

    /**
     * Extract email claim from token
     */
    public String extractEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public boolean validateToken(String token, String username) {
        String tokenUsername = extractUsername(token);
        return (username.equals(tokenUsername) && !isTokenExpired(token));
    }

    public String extractUsername(String token) {
        return getClaims(token).getSubject();
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
                .verifyWith( getSigningKey())
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
