package com.onlineshopping.order_service.controller;

import com.onlineshopping.order_service.dto.NewOrder;
import com.onlineshopping.order_service.dto.OrderResponse;
import com.onlineshopping.order_service.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/order")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/newOrder")
    public ResponseEntity<String> createOrder(@RequestBody NewOrder newOrder) {
        try {
            orderService.placeOrder(newOrder);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to create order" + e.getMessage());
        }
        return ResponseEntity.ok("Order created successfully");
    }
    // 2 Get Latest Order of User
    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrderById(@PathVariable String id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    // 3️⃣ Get Latest Order of User
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<OrderResponse>> getUserOrders(@PathVariable Long userId) {
        return ResponseEntity.ok(orderService.getUserOrders(userId));
    }

    // 4️⃣ Update Order Status
    @PutMapping("/{id}/status")
    public ResponseEntity<String> updateOrderStatus(@PathVariable String id,
                                                    @RequestParam String status) {
        orderService.updateOrderStatus(id, status);
        return ResponseEntity.ok("Order status updated to " + status);
    }

}
