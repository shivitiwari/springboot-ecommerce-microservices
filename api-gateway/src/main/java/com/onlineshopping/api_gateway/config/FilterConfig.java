package com.onlineshopping.api_gateway.config;

import com.onlineshopping.api_gateway.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FilterConfig — Defines ALL routes in code (not application.properties)
 *
 * WHY here instead of application.properties?
 *  - GatewayFilter (JwtAuthFilter) can only be attached per-route in Java config.
 *  - application.properties routes do NOT run GatewayFilters — only GlobalFilters run there.
 *
 * Caching Strategy:
 *  Gateway RedisCacheFilter is REMOVED. Service-level @Cacheable handles caching
 *  with proper @CacheEvict support on updates/deletes.
 *
 * Filter execution order per request:
 *  GlobalFilter (RateLimitingFilter -2) → GatewayFilter (JwtAuthFilter -1) → Downstream Service
 *
 * ⚠️ ROUTE ORDER MATTERS — Spring Cloud Gateway matches TOP TO BOTTOM.
 *    More specific paths (/api/auth/user/**, /api/auth/logout) MUST come
 *    BEFORE the general path (/api/auth/**), otherwise they are swallowed.
 *
 * Route summary (UserService has ONE controller under /api/auth/**):
 *  /api/auth/user/**          → USER-SERVICE    (JWT required  — profile, get user by id)
 *  /api/auth/logout            → USER-SERVICE    (JWT required  — must blacklist token)
 *  /api/auth/**                → USER-SERVICE    (No JWT        — public: login, register)
 *  GET /api/products/**        → PRODUCT-SERVICE (No JWT        — public browsing, aligns with ProductService SecurityConfig permitAll())
 *  POST/PUT/DELETE /api/products/** → PRODUCT-SERVICE (JWT required — admin operations)
 *  /api/orders/**              → ORDER-SERVICE   (JWT required  — all order operations)
 */
@Configuration
public class FilterConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;


    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // ─────────────────────────────────────────────────────────────
                // ROUTE 1: User Service — Protected user endpoints (JWT REQUIRED)
                // Matches: GET  /api/auth/user/{id}
                //          GET  /api/auth/user/profile
                //          PUT  /api/auth/user/profile
                //
                // ⚠️ Must be FIRST — more specific than /api/auth/**
                //    If /api/auth/** were first, it would match these too (no JWT).
                // ─────────────────────────────────────────────────────────────
                .route("user-service-user-endpoints", r -> r
                        .path("/api/auth/user/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter))   // JWT required
                        .uri("lb://USER-SERVICE"))

                // ─────────────────────────────────────────────────────────────
                // ROUTE 2: User Service — Logout (JWT REQUIRED)
                // Matches: POST /api/auth/logout
                //
                // ⚠️ Must be BEFORE /api/auth/** — logout needs the token
                //    to blacklist it. Without JWT filter here, anyone could
                //    call /logout without a valid token.
                // ─────────────────────────────────────────────────────────────
                .route("user-service-logout", r -> r
                        .path("/api/auth/logout")
                        .filters(f -> f
                                .filter(jwtAuthFilter))   // JWT required
                        .uri("lb://USER-SERVICE"))

                // ─────────────────────────────────────────────────────────────
                // ROUTE 3: User Service — Public auth (NO JWT)
                // Matches: POST /api/auth/register
                //          POST /api/auth/login
                //
                // ⚠️ Must be LAST among /api/auth/** routes.
                //    Routes 1 & 2 above are more specific and match first.
                //    This is the catch-all for remaining /api/auth/** paths.
                // ─────────────────────────────────────────────────────────────
                .route("user-service-auth", r -> r
                        .path("/api/auth/**")
                        .uri("lb://USER-SERVICE"))

                // ─────────────────────────────────────────────────────────────
                // ROUTE 4a: Product Service — Public READ (NO JWT)
                // Gateway : GET /api/products/**  or  GET /api/products
                // Service : GET /products/**      or  GET /products
                //
                // WHY no JWT?
                //  Product Service SecurityConfig has:
                //    .requestMatchers(HttpMethod.GET, "/products/**").permitAll()
                //  Meaning guests (unauthenticated) can browse products.
                //  Gateway must align — requiring JWT here would block all guests.
                //
                // Endpoints covered:
                //   GET /api/products          → /products        (list all, paginated)
                //   GET /api/products/5        → /products/5      (get by id)
                //   GET /api/products/search   → /products/search (search)
                //
                // ⚠️ Must be BEFORE Route 4b (same path, but method-specific).
                //    Spring Cloud Gateway checks method predicate — GET goes here,
                //    POST/PUT/DELETE fall through to Route 4b.
                // ─────────────────────────────────────────────────────────────
                .route("product-service-public", r -> r
                        .path("/api/products/**", "/api/products")
                        .and().method("GET")
                        .filters(f -> f
                                .rewritePath("/api/products/?(?<segment>.*)", "/products/${segment}"))
                        .uri("lb://PRODUCT-SERVICE"))

                // ─────────────────────────────────────────────────────────────
                // ROUTE 4b: Product Service — Protected WRITE (JWT REQUIRED)
                // Gateway : POST/PUT/DELETE /api/products/**
                // Service : POST/PUT/DELETE /products/**
                //
                // WHY JWT + ADMIN role check?
                //  Product Service SecurityConfig has:
                //    .requestMatchers(HttpMethod.POST,   "/products/**").hasRole("ADMIN")
                //    .requestMatchers(HttpMethod.PUT,    "/products/**").hasRole("ADMIN")
                //    .requestMatchers(HttpMethod.DELETE, "/products/**").hasRole("ADMIN")
                //  Gateway validates JWT + forwards X-User-Role → service enforces ADMIN role.
                //
                // Endpoints covered:
                //   POST   /api/products      → /products      (create product)
                //   PUT    /api/products/5    → /products/5    (update product)
                //   DELETE /api/products/5    → /products/5    (delete product)
                // ─────────────────────────────────────────────────────────────
                .route("product-service-protected", r -> r
                        .path("/api/products/**", "/api/products")
                        .and().method("POST", "PUT", "DELETE")
                        .filters(f -> f
                                .filter(jwtAuthFilter)    // JWT required, X-User-Role forwarded
                                .rewritePath("/api/products/?(?<segment>.*)", "/products/${segment}"))
                        .uri("lb://PRODUCT-SERVICE"))

                // ─────────────────────────────────────────────────────────────
                // ROUTE 5: Order Service (JWT + RewritePath)
                // Gateway : /api/orders/**
                // Service : /order/**
                // ─────────────────────────────────────────────────────────────
                .route("order-service", r -> r
                        .path("/api/orders/**")
                        .filters(f -> f
                                .filter(jwtAuthFilter)         // JWT required
                                .rewritePath("/api/orders/(?<segment>.*)", "/order/${segment}"))
                        .uri("lb://ORDER-SERVICE"))

                .build();
    }
}