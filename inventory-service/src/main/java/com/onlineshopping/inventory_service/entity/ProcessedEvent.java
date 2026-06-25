package com.onlineshopping.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "processed_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "order_id")
    private String orderId;

    @Column(nullable = false)
    private String result;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;
}

