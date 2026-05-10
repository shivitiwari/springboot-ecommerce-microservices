package com.onlineShopping.UserService.serviceimpl;

import com.onlineShopping.UserService.dto.CreateUserRequest;
import com.onlineShopping.UserService.dto.UpdateUser;
import com.onlineShopping.UserService.dto.UserResponse;
import com.onlineShopping.UserService.entity.User;
import com.onlineShopping.UserService.repository.UserRepository;
import com.onlineShopping.UserService.service.UserService;
import jakarta.transaction.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void saveUser(CreateUserRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already exists");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(Objects.requireNonNull(passwordEncoder.encode(request.getPassword())));
        user.setRole(request.getRole());

        userRepository.save(user);
    }

    @Override
    @Cacheable(value = "users", key = "#id")
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
        return mapToResponse(user);
    }

    @Override
    @Cacheable(value = "usersByUsername", key = "#username")
    public UserResponse getUserByUsername(String username) {
        User user = userRepository.findByUsername(username).orElseThrow(() -> new RuntimeException("User not found"));
        return mapToResponse(user);
    }

    @Override
    @CacheEvict(value = {"users", "usersByUsername"}, allEntries = true)
    public UserResponse updateUserProfile(String username, UpdateUser userDetails) {
        User user = userRepository.findByUsername(username).orElseThrow(() -> new RuntimeException("User not found"));
        if (userDetails.getUsername() != null && !userDetails.getUsername().equals(user.getUsername())) {
            if (userRepository.existsByUsername(userDetails.getUsername())) {
                throw new IllegalArgumentException("Username already exists");
            }
            user.setUsername(userDetails.getUsername());
        }
        if (userDetails.getEmail() != null && !userDetails.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByEmail(userDetails.getEmail())) {
                throw new IllegalArgumentException("Email already exists");
            }
            user.setEmail(userDetails.getEmail());
        }
        if (userDetails.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(userDetails.getPassword()));
        }
        userRepository.save(user);
        return mapToResponse(user);
    }

    private UserResponse mapToResponse(User user) {
        return UserResponse.builder().
                id(user.getId()).
                username(user.getUsername()).
                email(user.getEmail()).
                role(user.getRole()).
                build();
    }
}