package com.onlineshopping.order_service.service;

import com.onlineshopping.order_service.dto.NewOrder;
import com.onlineshopping.order_service.dto.OrderResponse;

import java.util.List;

public interface OrderService {
    void placeOrder(NewOrder order);

    OrderResponse getOrderById(String id);

    List<OrderResponse> getUserOrders(Long userId);

    void updateOrderStatus(String id, String status);
}

