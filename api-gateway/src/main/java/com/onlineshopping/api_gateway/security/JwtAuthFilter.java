package com.onlineshopping.api_gateway.security;

import com.onlineshopping.api_gateway.service.TokenBlacklistService;
import com.onlineshopping.api_gateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthFilter implements GatewayFilter, Ordered {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register"
            // NOTE: /api/auth/logout and /api/auth/user/** are protected.
            // They are on separate routes in FilterConfig.java with JWT filter attached.
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip filter for public endpoints
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        // Validate token structure and signature
        if (!jwtUtil.isTokenValid(token)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // Check if token is blacklisted (logout)
        return tokenBlacklistService.isTokenBlacklisted(token)
                .flatMap(isBlacklisted -> {
                    if (isBlacklisted) {
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    // Extract all claims from JWT
                    Claims claims = jwtUtil.extractAllClaims(token);
                    String userId = claims.get("userId", String.class);
                    String username = claims.getSubject();
                    String role = claims.get("role", String.class);

                    // Forward user info to downstream services as headers
                    ServerHttpRequest modifiedRequest = exchange.getRequest()
                            .mutate()
                            .header("X-User-Id", userId != null ? userId : "")
                            .header("X-Username", username != null ? username : "")
                            .header("X-User-Role", role != null ? role : "USER")
                            .build();

                    return chain.filter(exchange.mutate().request(modifiedRequest).build());
                });
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }
}
