package com.onlineShopping.UserService.service;

import com.onlineShopping.UserService.dto.CreateUserRequest;
import com.onlineShopping.UserService.dto.UpdateUser;
import com.onlineShopping.UserService.dto.UserResponse;

public interface UserService {

    void saveUser(CreateUserRequest createUserRequest);
    UserResponse getUserById(Long id);

    UserResponse getUserByUsername(String username);

    UserResponse updateUserProfile(String username, UpdateUser userDetails);
}
