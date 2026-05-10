package com.onlineShopping.UserService.client;

import com.onlineShopping.UserService.dto.OrderResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "order-service")
public interface OrderClient {

    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);
}