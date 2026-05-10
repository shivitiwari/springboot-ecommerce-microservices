package com.onlineShopping.UserService.security;

import com.onlineShopping.UserService.service.TokenBlacklistService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * @deprecated No longer used in microservices architecture.
 * The API Gateway validates JWT tokens and forwards trusted headers
 * (X-Username, X-UserId, X-Role) to downstream services.
 * UserService reads identity from those headers instead of re-parsing JWT.
 *
 * Kept for reference only. Do NOT register as a bean.
 */
@Deprecated
// @Component  -- REMOVED: Gateway handles JWT validation, this filter is not needed
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   CustomUserDetailsService customUserDetailsService,
                                   TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.customUserDetailsService = customUserDetailsService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = jwtUtil.extractTokenFromHeader(request.getHeader("Authorization"));

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                if (!tokenBlacklistService.isBlacklisted(token)) {
                    String username = jwtUtil.extractUsername(token);
                    UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);

                    if (jwtUtil.validateToken(token, userDetails.getUsername())) {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}

