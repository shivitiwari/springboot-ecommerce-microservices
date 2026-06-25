package com.onlineshopping.inventory_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory",
       uniqueConstraints = @UniqueConstraint(columnNames = "product_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false, unique = true)
    private Long productId;

    @Column(name = "product_name")
    private String productName;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * OPTIMISTIC LOCKING — @Version
     *
     * JPA appends: WHERE product_id = ? AND version = ?
     * to every UPDATE. If two threads try to decrement stock simultaneously:
     *   Thread A: reads version=0, decrements, saves → version becomes 1 ✅
     *   Thread B: reads version=0, decrements, saves → WHERE version=0 → 0 rows → throws OptimisticLockingFailureException ✅
     */
    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

