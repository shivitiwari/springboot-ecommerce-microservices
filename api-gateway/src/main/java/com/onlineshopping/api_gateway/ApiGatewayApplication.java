package com.onlineshopping.api_gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@SpringBootApplication
@EnableDiscoveryClient
@RestController
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    // Diagnostic endpoint — test if gateway itself works
    // Hit: http://localhost:8080/gateway/ping
    @GetMapping("/gateway/ping")
    public Mono<String> ping() {
        return Mono.just("Gateway is alive!");
    }
}
