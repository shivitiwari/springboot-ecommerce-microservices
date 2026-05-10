package com.onlineshopping.product_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Filter that trusts user information passed from API Gateway via headers.
 * API Gateway validates the JWT and forwards user details to downstream services.
 * 
 * Expected Headers from Gateway:
 * - X-User-Id: The user's ID
 * - X-Username: The authenticated username
 * - X-User-Role: The user's role (e.g., ADMIN, USER)
 */
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String userId = request.getHeader(HEADER_USER_ID);
        String username = request.getHeader(HEADER_USERNAME);
        String role = request.getHeader(HEADER_USER_ROLE);

        // If gateway has passed user info, set up the security context
        if (username != null && !username.isEmpty()) {
            List<SimpleGrantedAuthority> authorities = Collections.emptyList();
            
            if (role != null && !role.isEmpty()) {
                // Add ROLE_ prefix if not present (Spring Security convention)
                String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                authorities = List.of(new SimpleGrantedAuthority(roleWithPrefix));
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            
            // Store additional user info as details
            authentication.setDetails(new GatewayUserDetails(userId, username, role));
            
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Simple record to hold user details from gateway headers
     */
    public record GatewayUserDetails(String userId, String username, String role) {}
}

