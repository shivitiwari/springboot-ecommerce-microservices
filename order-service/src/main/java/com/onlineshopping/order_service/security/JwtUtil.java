package com.onlineshopping.order_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
@Deprecated
@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String SECRET_KEY;
    private static final long EXPIRATION_TIME = 1000 * 60 * 10;

    public String generateToken(String username) {
        return Jwts.builder().
                subject(username).
                issuedAt(new Date()).
                expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME)).
                signWith(getSigningKey()).
                compact();
    }

    public boolean validateToken(String token, String username) {
        String tokenUsername = getUsernameFromToken(token);
        return (username.equals(tokenUsername) && !isTokenExpired(token));
    }

    private String getUsernameFromToken(String token) {
        return getClaims(token).getSubject();
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
        Date expirationDate = getClaims(token).getExpiration();
        return expirationDate.before(new Date());
    }
}
