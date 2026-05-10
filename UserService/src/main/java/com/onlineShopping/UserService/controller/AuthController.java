package com.onlineShopping.UserService.controller;

import com.onlineShopping.UserService.dto.CreateUserRequest;
import com.onlineShopping.UserService.dto.LoginRequest;
import com.onlineShopping.UserService.dto.LoginResponse;
import com.onlineShopping.UserService.dto.UpdateUser;
import com.onlineShopping.UserService.entity.User;
import com.onlineShopping.UserService.repository.UserRepository;
import com.onlineShopping.UserService.service.TokenBlacklistService;
import com.onlineShopping.UserService.service.UserService;
import com.onlineShopping.UserService.security.JwtUtil;
import lombok.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthController(UserService userService,
                          UserRepository userRepository,
                          AuthenticationManager authenticationManager,
                          JwtUtil jwtUtil,
                          TokenBlacklistService tokenBlacklistService) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @PostMapping("/register")
    public ResponseEntity<@NonNull String> register(@RequestBody CreateUserRequest request) {
        userService.saveUser(request);
        return ResponseEntity.ok("User Successfully Registered");
    }

    @PostMapping("/login")
    public ResponseEntity<@NonNull LoginResponse> login(@RequestBody LoginRequest request) {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        
        // Fetch user to generate token with userId and role claims
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        String token = jwtUtil.generateToken(user);
        return ResponseEntity.ok(new LoginResponse(token));
    }
    @PostMapping("/logout")
    public ResponseEntity<String> logout(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String token = jwtUtil.extractTokenFromHeader(authorizationHeader);
        if (token == null) {
            return ResponseEntity.badRequest().body("Missing or invalid Authorization header");
        }

        tokenBlacklistService.blacklistToken(token, jwtUtil.extractExpiration(token));
        return ResponseEntity.ok("Logged out successfully");
    }

    /**
     * Get user by ID - Returns format compatible with Order Service
     * Order Service expects: { "id", "name", "email" }
     */
    @GetMapping("user/{id}")
    public ResponseEntity<?> getUserById(@PathVariable("id") Long id) {
        try {
            User user = userRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            // Return format expected by Order Service
            return ResponseEntity.ok(Map.of(
                    "id", user.getId(),
                    "name", user.getUsername(),  // Map username to name
                    "email", user.getEmail()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to get user id: " + e.getMessage());
        }
    }

    // GET user profile - reads X-Username header forwarded by API Gateway
    @GetMapping("user/profile")
    public ResponseEntity<?> getUserProfile(HttpServletRequest request) {
        try {
            String username = request.getHeader("X-Username");
            if (username == null || username.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
            }
            return ResponseEntity.ok(userService.getUserByUsername(username));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to get user profile: " + e.getMessage());
        }
    }

    // Update user profile - reads X-Username header forwarded by API Gateway
    @PutMapping("user/profile")
    public ResponseEntity<?> updateUserProfile(
            HttpServletRequest request,
            @RequestBody UpdateUser userDetails) {
        try {
            String username = request.getHeader("X-Username");
            if (username == null || username.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing user identity");
            }
            return ResponseEntity.ok(userService.updateUserProfile(username, userDetails));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to update user profile: " + e.getMessage());
        }
    }
}