package com.onlineshopping.product_service.controller;

import com.onlineshopping.product_service.dto.ApiResponse;
import com.onlineshopping.product_service.dto.CreateProduct;
import com.onlineshopping.product_service.dto.PagedResponse;
import com.onlineshopping.product_service.dto.ProductResponse;
import com.onlineshopping.product_service.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // Create product (admin only)
    @PostMapping
    public ResponseEntity<ApiResponse<String>> createProduct(@RequestBody @Valid CreateProduct product) {
        productService.createProduct(product);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<String>builder()
                        .success(true)
                        .message("Product created successfully")
                        .data(product.getName())
                        .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }
    //List all products with pagination
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProductResponse>>> getAllProducts(Pageable pageable) {

        Page<ProductResponse> page = productService.getAllProducts(pageable);

        PagedResponse<ProductResponse> pagedResponse =
                PagedResponse.<ProductResponse>builder()
                        .content(page.getContent())
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build();

        ApiResponse<PagedResponse<ProductResponse>> response =
                ApiResponse.<PagedResponse<ProductResponse>>builder()
                        .success(true)
                        .message("Products fetched successfully")
                        .data(pagedResponse)
                        .build();

        return ResponseEntity.ok(response);
    }
    //Update product details
    @PutMapping("/{id}")
    public ResponseEntity<Void> updateProduct(@PathVariable Long id, @RequestBody @Valid CreateProduct product) {
        productService.updateProduct(id, product);
        return ResponseEntity.noContent().build();
    }
    //delete a product by id
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
    //Search products by name or description
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PagedResponse<ProductResponse>>> searchProducts(
            @RequestParam String query,
            Pageable pageable) {
        Page<ProductResponse> page = productService.searchProducts(query, pageable);
        return ResponseEntity.ok(
                ApiResponse.<PagedResponse<ProductResponse>>builder()
                        .success(true)
                        .message("Products search results")
                        .data(PagedResponse.<ProductResponse>builder()
                                .content(page.getContent())
                                .page(page.getNumber())
                                .size(page.getSize())
                                .totalElements(page.getTotalElements())
                                .totalPages(page.getTotalPages())
                                .build())
                        .build());
    }

}