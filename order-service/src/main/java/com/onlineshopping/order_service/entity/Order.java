package com.onlineshopping.order_service.entity;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document
@AllArgsConstructor
@Data
@NoArgsConstructor
public class Order {
    @Id
    private String id;
    @NotNull
    private Long userId;
    @NotEmpty
    private List<OrderItem> items;
    @NotNull
    private Double totalAmount;
    @NotNull
    private String status; // PENDING, CONFIRMED, DELIVERED
    private LocalDateTime createdAt;
}


