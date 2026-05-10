package com.onlineshopping.order_service.dto;

import com.onlineshopping.order_service.entity.OrderItem;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class OrderEvent {
    private  String orderId;
    private Long userId;
    private List<OrderItem> items;
    private Double totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
