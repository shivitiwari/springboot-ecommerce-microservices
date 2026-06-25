package com.onlineshopping.inventory_service.service;

import com.onlineshopping.inventory_service.dto.OrderEvent;
import com.onlineshopping.inventory_service.entity.Inventory;
import com.onlineshopping.inventory_service.entity.ProcessedEvent;
import com.onlineshopping.inventory_service.exception.InsufficientStockException;
import com.onlineshopping.inventory_service.exception.InventoryNotFoundException;
import com.onlineshopping.inventory_service.producer.InventoryEventProducer;
import com.onlineshopping.inventory_service.repository.InventoryRepository;
import com.onlineshopping.inventory_service.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final InventoryEventProducer eventProducer;

    private static final String LOCK_PREFIX = "lock:inventory:";
    private static final long LOCK_TTL_SECONDS = 10L;

    public InventoryServiceImpl(InventoryRepository inventoryRepository,
                                ProcessedEventRepository processedEventRepository,
                                RedisTemplate<String, String> redisTemplate,
                                InventoryEventProducer eventProducer) {
        this.inventoryRepository = inventoryRepository;
        this.processedEventRepository = processedEventRepository;
        this.redisTemplate = redisTemplate;
        this.eventProducer = eventProducer;
    }

    @Override
    public void processOrderEvent(OrderEvent event, Acknowledgment ack) {
        String orderId = event.getOrderId();

        // ── STEP 1: Idempotency check ────────────────────────────────────────
        if (processedEventRepository.existsById(orderId)) {
            log.info("[IDEMPOTENT] orderId={} already processed — skipping and committing offset", orderId);
            ack.acknowledge();
            return;
        }

        try {
            // ── STEP 2: Reserve stock for every item in the order ────────────
            for (var item : event.getItems()) {
                reserveStock(item.getProductId(), item.getQuantity(), orderId);
            }

            // ── STEP 3: Persist idempotency record ───────────────────────────
            processedEventRepository.save(ProcessedEvent.builder()
                    .orderId(orderId)
                    .result("SUCCESS")
                    .processedAt(LocalDateTime.now())
                    .build());

            // ── STEP 4: Publish CONFIRMED event to order-status-updated ──────
            eventProducer.publishOrderConfirmed(event);

            // ── STEP 5: Commit offset (only AFTER successful DB update) ──────
            ack.acknowledge();
            log.info("[SUCCESS] orderId={} — stock reserved and offset committed", orderId);

        } catch (InsufficientStockException | OptimisticLockingFailureException e) {
            log.error("[FAILURE] Inventory failure for orderId={}: {}", orderId, e.getMessage());

            // Persist FAILED result — prevents infinite retry on business logic failure
            processedEventRepository.save(ProcessedEvent.builder()
                    .orderId(orderId)
                    .result("FAILED")
                    .processedAt(LocalDateTime.now())
                    .build());

            // Publish failure → triggers Order Service Saga compensation
            eventProducer.publishInventoryFailed(event, e.getMessage());

            // Commit offset — business failures should not retry
            ack.acknowledge();
        }
        // Note: If a DB/Redis infrastructure exception occurs (NOT caught above),
        // ack.acknowledge() is never called → Kafka redelivers → natural retry for infra failures.
    }

    /**
     * Reserves (decrements) stock for one product using two-layer locking.
     *
     * Layer 1 (cross-instance): Redis SETNX distributed lock
     * Layer 2 (within-instance): JPA @Version optimistic lock
     */
    @Transactional
    public void reserveStock(Long productId, Integer quantity, String orderId) {
        String lockKey = LOCK_PREFIX + productId;

        // ── Layer 1: Redis SETNX — cross-instance lock ───────────────────────
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, orderId, LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(acquired)) {
            throw new InsufficientStockException(
                "Could not acquire inventory lock for productId=" + productId +
                " (concurrent update in progress). orderId=" + orderId);
        }

        try {
            // ── Layer 2: @Version — within-instance optimistic lock ──────────
            Inventory inventory = inventoryRepository.findByProductId(productId)
                    .orElseThrow(() -> new InventoryNotFoundException(
                        "ProductId " + productId + " not found in inventory. " +
                        "Initialize it first via POST /inventory/init"));

            if (inventory.getQuantity() < quantity) {
                throw new InsufficientStockException(String.format(
                    "Insufficient stock for productId=%d — required: %d, available: %d",
                    productId, quantity, inventory.getQuantity()));
            }

            inventory.setQuantity(inventory.getQuantity() - quantity);
            inventoryRepository.save(inventory);

        } finally {
            // ALWAYS release the Redis lock — even on exception
            redisTemplate.delete(lockKey);
        }
    }
}

