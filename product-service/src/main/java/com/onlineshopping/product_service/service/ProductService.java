package com.onlineshopping.product_service.service;

import com.onlineshopping.product_service.dto.CreateProduct;
import com.onlineshopping.product_service.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    void createProduct(CreateProduct createProduct);

    Page<ProductResponse> getAllProducts(Pageable pageable);

    ProductResponse getProductById(Long id);

    void updateProduct(Long id, CreateProduct product);

    void deleteProduct(Long id);

    Page<ProductResponse> searchProducts(String query, Pageable pageable);
}
