package com.onlineshopping.order_service.client;

import com.onlineshopping.order_service.dto.ApiResponse;
import com.onlineshopping.order_service.dto.PagedResponse;
import com.onlineshopping.order_service.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "product-service")
public interface ProductClient {
    @GetMapping("/api/products/{id}")
    ProductResponse getProductById(@PathVariable("id") Long id);

    @GetMapping("/products")
    ApiResponse<PagedResponse<ProductResponse>> getAllProducts(
            @RequestParam("page") int page,
            @RequestParam("size") int size
    );

    @GetMapping("/products/search")
    ApiResponse<PagedResponse<ProductResponse>> searchProducts(
            @RequestParam("query") String query,
            @RequestParam("page") int page,
            @RequestParam("size") int size
    );
}
