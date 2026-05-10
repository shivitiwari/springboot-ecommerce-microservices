package com.onlineshopping.api_gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        log.info("Incoming Request -> {} {}", method, path);

        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    if (exchange.getResponse().getStatusCode() != null) {
                        log.info("Response Status-> {}",
                                exchange.getResponse().getStatusCode().value());
                    }
                }));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
