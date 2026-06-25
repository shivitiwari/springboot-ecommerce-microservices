package com.onlineshopping.inventory_service.controller;

import com.onlineshopping.inventory_service.dto.InitInventoryRequest;
import com.onlineshopping.inventory_service.dto.RestockRequest;
import com.onlineshopping.inventory_service.entity.Inventory;
import com.onlineshopping.inventory_service.repository.InventoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryRepository inventoryRepository;

    public InventoryController(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /** GET /inventory/{productId} — Check stock level for a product (PUBLIC) */
    @GetMapping("/{productId}")
    public ResponseEntity<?> getStock(@PathVariable Long productId) {
        return inventoryRepository.findByProductId(productId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Product " + productId + " not found in inventory"));
    }

    /** GET /inventory — List all inventory items (ADMIN) */
    @GetMapping
    public ResponseEntity<List<Inventory>> getAllInventory() {
        return ResponseEntity.ok(inventoryRepository.findAll());
    }

    /** POST /inventory/init — Initialize a new product's stock (ADMIN) */
    @PostMapping("/init")
    public ResponseEntity<String> initInventory(@RequestBody InitInventoryRequest request) {
        if (inventoryRepository.findByProductId(request.getProductId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Inventory for productId=" + request.getProductId() + " already exists");
        }
        Inventory inventory = Inventory.builder()
                .productId(request.getProductId())
                .productName(request.getProductName())
                .quantity(request.getQuantity())
                .build();
        inventoryRepository.save(inventory);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body("Inventory initialized: productId=" + request.getProductId() +
                      ", quantity=" + request.getQuantity());
    }

    /** PUT /inventory/{productId}/restock — Add quantity (ADMIN) */
    @PutMapping("/{productId}/restock")
    public ResponseEntity<?> restock(@PathVariable Long productId,
                                     @RequestBody RestockRequest request) {
        return inventoryRepository.findByProductId(productId)
                .map(inv -> {
                    inv.setQuantity(inv.getQuantity() + request.getQuantity());
                    inventoryRepository.save(inv);
                    return ResponseEntity.ok("Restocked productId=" + productId +
                                             " by " + request.getQuantity() +
                                             " units. New stock: " + inv.getQuantity());
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Product " + productId + " not found in inventory"));
    }
}

