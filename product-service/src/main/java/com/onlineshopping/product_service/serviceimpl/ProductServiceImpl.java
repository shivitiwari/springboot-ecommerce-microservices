package com.onlineshopping.product_service.serviceimpl;

import com.onlineshopping.product_service.dto.CreateProduct;
import com.onlineshopping.product_service.dto.ProductResponse;
import com.onlineshopping.product_service.entity.Category;
import com.onlineshopping.product_service.entity.Product;
import com.onlineshopping.product_service.exception.CategoryNotFoundException;
import com.onlineshopping.product_service.exception.ProductNotFoundException;
import com.onlineshopping.product_service.repo.CategoryRepository;
import com.onlineshopping.product_service.repo.ProductRepository;
import com.onlineshopping.product_service.service.ProductService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of ProductService with Redis caching.
 * 
 * Cache Strategy:
 * - products: Individual product by ID (TTL: 5 min)
 * - productPages: Paginated product lists (TTL: 3 min)
 * - productSearch: Search results (TTL: 2 min)
 */
@Service
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductServiceImpl(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Caching(evict = {
            @CacheEvict(value = "products", allEntries = true),
            @CacheEvict(value = "productPages", allEntries = true),
            @CacheEvict(value = "productSearch", allEntries = true)
    })
    public void createProduct(CreateProduct createProduct) {
        Category category = categoryRepository.findById(createProduct.getCategoryId())
                .orElseThrow(() -> new CategoryNotFoundException(createProduct.getCategoryId()));
        Product product = new Product();
        product.setName(createProduct.getName());
        product.setDescription(createProduct.getDescription());
        product.setPrice(createProduct.getPrice());
        product.setStock(createProduct.getStock());
        product.setCategory(category);
        productRepository.save(product);
    }

    @Override
    @Cacheable(value = "productPages", key = "#pageable.pageNumber + '-' + #pageable.pageSize")
    public Page<ProductResponse> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::mapToResponse);
    }

    private ProductResponse mapToResponse(Product product) {
        return ProductResponse.builder()
                .productId(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stock(product.getStock())
                .categoryId(product.getCategory().getId())
                .categoryName(product.getCategory().getName())
                .build();
    }

    @Override
    @Cacheable(value = "products", key = "#id")
    public ProductResponse getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return mapToResponse(product);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "products", key = "#id"),
            @CacheEvict(value = "productPages", allEntries = true),
            @CacheEvict(value = "productSearch", allEntries = true)
    })
    public void updateProduct(Long id, CreateProduct updateProduct) {
        Category category = categoryRepository.findById(updateProduct.getCategoryId())
                .orElseThrow(() -> new CategoryNotFoundException(updateProduct.getCategoryId()));
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        product.setName(updateProduct.getName());
        product.setDescription(updateProduct.getDescription());
        product.setPrice(updateProduct.getPrice());
        product.setStock(updateProduct.getStock());
        product.setCategory(category);
        productRepository.save(product);
    }

    @Override
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "products", key = "#id"),
            @CacheEvict(value = "productPages", allEntries = true),
            @CacheEvict(value = "productSearch", allEntries = true)
    })
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        productRepository.delete(product);
    }

    @Override
    @Cacheable(value = "productSearch", key = "#query + '-' + #pageable.pageNumber")
    public Page<ProductResponse> searchProducts(String query, Pageable pageable) {
        return productRepository.searchProducts(query, pageable)
                .map(this::mapToResponse);
    }
}

