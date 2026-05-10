package com.onlineshopping.order_service.dto;

import com.onlineshopping.order_service.entity.OrderItem;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewOrder {
    @NotNull(message = "userId is required")
    private Long userId;

    @NotEmpty(message = "Order must have at least one item")
    private List<OrderItem> items;
}
