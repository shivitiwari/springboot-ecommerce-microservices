package com.onlineshopping.product_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for Product Service.
 * 
 * This service trusts user information from API Gateway headers.
 * JWT validation is handled centrally at the API Gateway level.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final GatewayAuthFilter gatewayAuthFilter;

    public SecurityConfig(GatewayAuthFilter gatewayAuthFilter) {
        this.gatewayAuthFilter = gatewayAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Admin only - Create, Update, Delete products
                        .requestMatchers(HttpMethod.POST, "/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/products/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/products/**").hasRole("ADMIN")
                        // Admin only - Create, Update, Delete categories
                        .requestMatchers(HttpMethod.POST, "/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/categories/**").hasRole("ADMIN")
                        // Public access - Read products and categories (GET requests)
                        .requestMatchers(HttpMethod.GET, "/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/categories/**").permitAll()
                        // Actuator endpoints for health checks
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
