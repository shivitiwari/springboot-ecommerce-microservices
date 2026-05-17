package com.onlineshopping.order_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.RequestContextFilter;

@EnableFeignClients
@EnableDiscoveryClient
@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

	/**
	 * Makes the HTTP request context inheritable by child threads.
	 * This ensures RequestContextHolder.getRequestAttributes() works
	 * inside Feign interceptors even if called from a different thread
	 * (e.g., Resilience4j circuit breaker or Feign async execution).
	 */
	@Bean
	public RequestContextFilter requestContextFilter() {
		RequestContextFilter filter = new RequestContextFilter();
		filter.setThreadContextInheritable(true);
		return filter;
	}
}
