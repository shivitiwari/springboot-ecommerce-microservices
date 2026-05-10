package com.onlineshopping.api_gateway.cache;

import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class RedisCacheFilter implements GatewayFilter, Ordered {

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Value("${cache.ttl.seconds:300}")
    private long  cacheTtlSeconds;

    public RedisCacheFilter(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    /* * Caching Filter Logic:
     * 1. For GET requests, generate a cache key based on the request URI and query parameters.
     * 2. Check Redis for a cached response using the generated key.
     * 3. If a cached response exists (cache hit), return it directly with an "X-Cache: HIT" header.
     * 4. If no cached response exists (cache miss), proceed with the request and capture the response body.
     * 5. Store the captured response in Redis with a TTL (e.g., 5 minutes) and return it to the client with an "X-Cache: MISS" header.
     * 6. Only cache GET requests to ensure that POST, PUT, DELETE, etc., are not cached.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Only cache GET requests
        if (!request.getMethod().equals(HttpMethod.GET)) {
            return chain.filter(exchange);
        }
        String query = request.getURI().getQuery();
        String cacheKey = "cache:" +
                request.getURI().getPath() +
                (query != null ? "?" + query : "");

        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(cachedResponse -> {
                    // Cache HIT — return cached data directly
                    exchange.getResponse().getHeaders()
                            .set("X-Cache", "HIT");
                    exchange.getResponse().getHeaders()
                            .set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                    DataBuffer buffer = exchange.getResponse()
                            .bufferFactory()
                            .wrap(cachedResponse.getBytes(StandardCharsets.UTF_8));
                    return exchange.getResponse().writeWith(Mono.just(buffer));
                })
                .switchIfEmpty(
                        // Cache MISS — proceed and cache the response
                        cacheResponse(exchange, chain, cacheKey)
                );
    }
    /* * Cache Response Logic:
     * 1. Decorate the ServerHttpResponse to capture the response body.
     * 2. When the response body is written, collect the data buffers and convert them into a byte array.
     * 3. Convert the byte array to a String (assuming JSON response) and store it in Redis with the generated cache key and TTL.
     * 4. Set the "X-Cache: MISS" header to indicate that the response was not served from cache.
     * 5. Write the original response body back to the client.
     */
    private Mono<Void> cacheResponse(ServerWebExchange exchange,
                                     GatewayFilterChain chain, String cacheKey) {
        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(
                exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return Flux.from(body)
                        .collectList()
                        .flatMap(dataBuffers -> {
                            byte[] bytes = dataBuffers.stream()
                                    .map(b -> {
                                        byte[] arr = new byte[b.readableByteCount()];
                                        b.read(arr);
                                        DataBufferUtils.release(b);
                                        return arr;
                                    })
                                    .reduce(new byte[0], (a, b) -> {
                                        byte[] result = new byte[a.length + b.length];
                                        System.arraycopy(a, 0, result, 0, a.length);
                                        System.arraycopy(b, 0, result, a.length, b.length);
                                        return result;
                                    });

                            String responseBody = new String(bytes, StandardCharsets.UTF_8);

                            // Store in Redis with TTL
                            redisTemplate.opsForValue()
                                    .set(cacheKey, responseBody, cacheTtlSeconds)
                                    .subscribe();

                            exchange.getResponse().getHeaders().set("X-Cache", "MISS");
                            DataBuffer buffer = exchange.getResponse()
                                    .bufferFactory().wrap(bytes);
                            return super.writeWith(Mono.just(buffer));
                        });
            }
        };

        return chain.filter(exchange.mutate().response(decorator).build());
    }

    @Override
    public int getOrder() {
        return 0;
    }
}