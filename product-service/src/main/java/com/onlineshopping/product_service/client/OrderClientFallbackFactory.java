package com.onlineshopping.product_service.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * Fallback Factory for OrderClient.
 * 
 * Provides fallback responses when Order Service is unavailable.
 * This ensures Product Service remains functional even when Order Service is down.
 */
@Component
public class OrderClientFallbackFactory implements FallbackFactory<OrderClient> {

    private static final Logger log = LoggerFactory.getLogger(OrderClientFallbackFactory.class);

    @Override
    public OrderClient create(Throwable cause) {
        log.error("Order Service is unavailable. Fallback triggered: {}", cause.getMessage());
        
        return new OrderClient() {
            @Override
            public OrderResponse getLatestUserOrder(Long userId) {
                log.warn("Fallback: getLatestUserOrder for userId={}", userId);
                // Return null or empty response - caller should handle this gracefully
                return null;
            }

            @Override
            public Boolean hasUserOrderedProduct(Long userId, Long productId) {
                log.warn("Fallback: hasUserOrderedProduct for userId={}, productId={}", userId, productId);
                // Default to false when Order Service is unavailable
                return false;
            }
        };
    }
}

