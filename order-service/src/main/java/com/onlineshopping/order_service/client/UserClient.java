package com.onlineshopping.order_service.client;

import com.onlineshopping.order_service.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserClient {
    @GetMapping("/api/auth/user/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);
}
