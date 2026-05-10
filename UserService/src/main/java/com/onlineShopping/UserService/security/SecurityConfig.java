package com.onlineShopping.UserService.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security Configuration for UserService.
 *
 * WHY NO JWT FILTER HERE?
 * ========================
 * In our microservices architecture, the API Gateway is responsible for:
 *   1. Validating JWT tokens on every incoming request
 *   2. Rejecting expired/invalid/blacklisted tokens
 *   3. Forwarding trusted headers (X-Username, X-UserId, X-Role) to downstream services
 *
 * Therefore, UserService does NOT need to re-validate the JWT on every request.
 * It trusts the Gateway and reads identity from forwarded headers.
 *
 * UserService only needs JwtUtil for:
 *   - Generating tokens at login (/api/auth/login)
 *   - Extracting expiration at logout (/api/auth/logout) to blacklist the token
 *
 * IMPORTANT: In production, ensure UserService is NOT directly accessible
 * from the internet — only through the API Gateway (network-level restriction).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration
    ) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {
        return httpSecurity
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints - no auth needed
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // Actuator for Eureka health checks
                        .requestMatchers("/actuator/**").permitAll()
                        // All other requests are trusted from API Gateway
                        // Gateway already validated JWT and forwards X-Username header
                        .anyRequest().permitAll()
                )
                .build();

    }
}
