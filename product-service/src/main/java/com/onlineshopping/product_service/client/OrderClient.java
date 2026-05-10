package com.onlineshopping.product_service.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Feign Client for communicating with Order Service.
 * 
 * This client can be used to:
 * - Get latest user orders
 * - Check if a product was recently ordered
 * - Show "recently ordered" status on products
 * 
 * Note: Requires Order Service to be running and registered with Eureka.
 */
@FeignClient(name = "order-service", fallbackFactory = OrderClientFallbackFactory.class)
public interface OrderClient {

    /**
     * Get the latest order for a specific user.
     * 
     * @param userId The user's ID
     * @return OrderResponse containing order details
     */
    @GetMapping("/order/user/{userId}")
    OrderResponse getLatestUserOrder(@PathVariable("userId") Long userId);

    /**
     * Check if a product has been ordered by a user.
     * 
     * @param userId The user's ID
     * @param productId The product's ID
     * @return true if the user has ordered this product
     */
    @GetMapping("/order/user/{userId}/product/{productId}/exists")
    Boolean hasUserOrderedProduct(@PathVariable("userId") Long userId, 
                                   @PathVariable("productId") Long productId);
}

