# AI Service (`ai-service`) — Spring AI Integration

> **Last Updated:** June 25, 2026  
> **Version:** 1.0.0  
> **Port:** `8086`  
> **Service Name:** `AI-SERVICE`  
> **Framework:** Spring AI 1.0.0 (GA)

---

## 📋 Overview

| Property | Value |
|----------|-------|
| **Service Name** | `AI-SERVICE` |
| **Port** | `8086` |
| **Base Path** | `/ai` |
| **Gateway Route** | `/api/ai/**` → `lb://AI-SERVICE` |
| **Database** | PostgreSQL + pgvector (`ai_db`) |
| **Vector Store** | PGVector (product embeddings for semantic search) |
| **LLM Provider** | OpenAI GPT-4o-mini (production) / Ollama (local dev) |
| **Embedding Model** | OpenAI `text-embedding-3-small` / Ollama `nomic-embed-text` |
| **Registry** | Eureka Server (`http://localhost:8761/eureka`) |
| **Cache** | Redis — conversation history, recommendation cache |

---

## 🏗️ Role in System Architecture

```
                         Client
                           │
                      API Gateway (:8080)
                           │
         ┌─────────────────┼─────────────────────────────────────────┐
         │                 │                 │              │         │
    User Service    Product Service    Order Service   Inventory   AI Service
      (:8081)         (:8082)           (:8083)        (:8084)     (:8086)
                                                                      │
                                                          ┌───────────┼───────────┐
                                                          │           │           │
                                                      Chat/RAG   Embeddings   Functions
                                                          │           │           │
                                                    ┌─────┴────┐  ┌──┴──┐   ┌───┴────┐
                                                    │ OpenAI / │  │PG   │   │ Feign  │
                                                    │ Ollama   │  │Vector│   │ Clients│
                                                    └──────────┘  └─────┘   └────────┘
```

### AI Service Capabilities

| Feature | Description | Spring AI Component |
|---------|-------------|---------------------|
| **Shopping Assistant Chatbot** | Natural language Q&A about products, orders, policies | `ChatClient` + Function Calling |
| **Semantic Product Search** | Find products by meaning, not just keywords | `EmbeddingModel` + `VectorStore` |
| **Product Recommendations** | "Similar products", "Frequently bought together" | `VectorStore` similarity search |
| **Smart Product Descriptions** | Auto-generate SEO product descriptions | `ChatClient` with prompt templates |
| **Order Insights** | "Summarize my order history", "When will my order arrive?" | `ChatClient` + Function Calling |
| **Review Summarization** | Summarize product reviews into pros/cons | `ChatClient` structured output |
| **Fraud Detection Hints** | Flag suspicious order patterns | `ChatClient` with structured output |
| **Personalized Notifications** | AI-crafted email subject lines & content | Used by Notification Service via Feign |

---

## 📁 Project Structure

```
ai-service/
├── src/main/java/com/onlineshopping/ai_service/
│   ├── AiServiceApplication.java                    # @SpringBootApplication @EnableDiscoveryClient
│   │
│   ├── config/
│   │   ├── AiConfig.java                           # ChatClient, EmbeddingModel beans
│   │   ├── VectorStoreConfig.java                  # PGVector setup + schema init
│   │   ├── RedisConfig.java                        # Conversation memory cache
│   │   └── FeignConfig.java                        # Header propagation for inter-service calls
│   │
│   ├── controller/
│   │   ├── ChatController.java                     # POST /ai/chat — Shopping assistant
│   │   ├── RecommendationController.java           # GET /ai/recommendations/{productId}
│   │   ├── SearchController.java                   # GET /ai/search?q=... — Semantic search
│   │   └── ContentController.java                  # POST /ai/generate/description — Generate text
│   │
│   ├── service/
│   │   ├── ChatService.java                        # Chat with memory + function calling
│   │   ├── EmbeddingService.java                   # Embed & store product vectors
│   │   ├── RecommendationService.java              # Similarity-based recommendations
│   │   ├── ContentGenerationService.java           # Product descriptions, email content
│   │   └── ProductSyncService.java                 # Kafka consumer — sync product catalog to vector store
│   │
│   ├── function/                                    # Spring AI Function Calling tools
│   │   ├── ProductSearchFunction.java              # Tool: search products by criteria
│   │   ├── OrderStatusFunction.java                # Tool: get user's order status
│   │   ├── UserProfileFunction.java                # Tool: get user preferences
│   │   └── InventoryCheckFunction.java             # Tool: check product availability
│   │
│   ├── dto/
│   │   ├── ChatRequest.java
│   │   ├── ChatResponse.java
│   │   ├── RecommendationResponse.java
│   │   ├── SemanticSearchResponse.java
│   │   ├── GenerateDescriptionRequest.java
│   │   └── ProductEmbeddingDocument.java
│   │
│   ├── client/                                      # Feign clients to other services
│   │   ├── ProductClient.java
│   │   ├── OrderClient.java
│   │   └── UserClient.java
│   │
│   ├── kafka/
│   │   └── ProductEventConsumer.java               # Sync product changes to vector store
│   │
│   └── security/
│       ├── GatewayAuthFilter.java                  # Standard X-User-* header filter
│       └── SecurityConfig.java
│
└── src/main/resources/
    ├── application.properties
    ├── prompts/
    │   ├── shopping-assistant.st                    # System prompt for chatbot
    │   ├── product-description.st                  # Prompt template for descriptions
    │   ├── review-summary.st                       # Prompt template for review summarization
    │   └── notification-content.st                 # Prompt template for email generation
    └── schema.sql                                   # PGVector extension + table setup
```

---

## 🤖 Core Implementation

### 1. `AiConfig.java` — ChatClient & Model Configuration

```java
package com.onlineshopping.ai_service.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * ChatClient — the primary interface for all LLM interactions in Spring AI.
     * 
     * Advisors:
     *   - MessageChatMemoryAdvisor: maintains conversation history per session
     * 
     * Default System Prompt: loaded from prompts/shopping-assistant.st
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                    You are a helpful shopping assistant for an online store.
                    You can help users find products, check order status, get recommendations,
                    and answer questions about store policies.
                    
                    Be concise, friendly, and always suggest relevant products when appropriate.
                    If you don't know something, say so — don't make up information.
                    
                    Available tools you can use:
                    - searchProducts: Find products by name, category, or description
                    - getOrderStatus: Check the status of a user's order
                    - checkInventory: Verify if a product is in stock
                    - getUserProfile: Get user preferences for personalization
                    """)
                .defaultAdvisors(new MessageChatMemoryAdvisor(new InMemoryChatMemory()))
                .build();
    }
}
```

---

### 2. `ChatController.java` — Shopping Assistant Chatbot

```java
package com.onlineshopping.ai_service.controller;

import com.onlineshopping.ai_service.dto.ChatRequest;
import com.onlineshopping.ai_service.dto.ChatResponse;
import com.onlineshopping.ai_service.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * POST /ai/chat — Conversational shopping assistant
     * 
     * Supports:
     *   - "Show me laptops under 50000"        → calls searchProducts function
     *   - "What's the status of my last order?" → calls getOrderStatus function
     *   - "Is product #5 in stock?"             → calls checkInventory function
     *   - "Recommend something similar to X"    → vector similarity search
     *   - General shopping questions            → LLM direct response
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        ChatResponse response = chatService.chat(request.getMessage(), userId, request.getSessionId());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /ai/chat/stream — Streaming response (SSE)
     * For real-time typing effect in frontend
     */
    @PostMapping("/chat/stream")
    public reactor.core.publisher.Flux<String> chatStream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        return chatService.chatStream(request.getMessage(), userId, request.getSessionId());
    }
}
```

---

### 3. `ChatService.java` — Chat with Function Calling

```java
package com.onlineshopping.ai_service.service;

import com.onlineshopping.ai_service.dto.ChatResponse;
import com.onlineshopping.ai_service.function.InventoryCheckFunction;
import com.onlineshopping.ai_service.function.OrderStatusFunction;
import com.onlineshopping.ai_service.function.ProductSearchFunction;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ProductSearchFunction productSearchFunction;
    private final OrderStatusFunction orderStatusFunction;
    private final InventoryCheckFunction inventoryCheckFunction;

    public ChatService(ChatClient chatClient,
                       ProductSearchFunction productSearchFunction,
                       OrderStatusFunction orderStatusFunction,
                       InventoryCheckFunction inventoryCheckFunction) {
        this.chatClient = chatClient;
        this.productSearchFunction = productSearchFunction;
        this.orderStatusFunction = orderStatusFunction;
        this.inventoryCheckFunction = inventoryCheckFunction;
    }

    /**
     * Synchronous chat with function calling.
     * 
     * Spring AI Function Calling:
     *   1. User says: "Is the Laptop in stock?"
     *   2. LLM decides to call `checkInventory` function with {"productName": "Laptop"}
     *   3. Spring AI intercepts, calls InventoryCheckFunction.apply()
     *   4. Function calls Inventory Service via Feign → returns stock count
     *   5. LLM receives function result, generates natural language response
     *   6. User sees: "Yes! The Laptop is in stock with 42 units available."
     */
    public ChatResponse chat(String message, String userId, String sessionId) {
        String conversationId = sessionId != null ? sessionId : "user-" + userId;

        String response = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .functions("searchProducts", "getOrderStatus", "checkInventory")
                .call()
                .content();

        return ChatResponse.builder()
                .message(response)
                .sessionId(conversationId)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Streaming chat — returns tokens as they're generated (Server-Sent Events)
     */
    public Flux<String> chatStream(String message, String userId, String sessionId) {
        String conversationId = sessionId != null ? sessionId : "user-" + userId;

        return chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .functions("searchProducts", "getOrderStatus", "checkInventory")
                .stream()
                .content();
    }
}
```

---

### 4. Function Calling — `ProductSearchFunction.java`

```java
package com.onlineshopping.ai_service.function;

import com.onlineshopping.ai_service.client.ProductClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI Function Calling — Tool for the LLM to search products.
 * 
 * HOW IT WORKS:
 *   1. LLM receives user message: "Show me phones under 30000"
 *   2. LLM decides it needs product data → calls this function
 *   3. Spring AI serializes the request, calls apply()
 *   4. This function calls Product Service via Feign
 *   5. Result returned to LLM → LLM generates natural language response
 * 
 * The @Description annotation tells the LLM WHEN to use this function.
 */
@Configuration
public class ProductSearchFunction {

    private final ProductClient productClient;

    public ProductSearchFunction(ProductClient productClient) {
        this.productClient = productClient;
    }

    public record SearchRequest(String query, Double maxPrice, String category) {}
    public record SearchResponse(String products, int totalResults) {}

    @Bean
    @Description("Search for products in the online store by name, description, category, or price range. " +
                 "Use this when the user asks about available products, wants recommendations, or searches for items.")
    public Function<SearchRequest, SearchResponse> searchProducts() {
        return request -> {
            try {
                // Call Product Service via Feign
                var result = productClient.searchProducts(request.query(), 0, 10);
                return new SearchResponse(result.toString(), result.getData().getTotalElements().intValue());
            } catch (Exception e) {
                return new SearchResponse("Product search is temporarily unavailable.", 0);
            }
        };
    }
}
```

---

### 5. Function Calling — `OrderStatusFunction.java`

```java
package com.onlineshopping.ai_service.function;

import com.onlineshopping.ai_service.client.OrderClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class OrderStatusFunction {

    private final OrderClient orderClient;

    public OrderStatusFunction(OrderClient orderClient) {
        this.orderClient = orderClient;
    }

    public record OrderStatusRequest(Long userId) {}
    public record OrderStatusResponse(String orderId, String status, String details) {}

    @Bean
    @Description("Get the current status of a user's latest order. " +
                 "Use this when the user asks about their order status, delivery, or tracking.")
    public Function<OrderStatusRequest, OrderStatusResponse> getOrderStatus() {
        return request -> {
            try {
                var order = orderClient.getUserOrders(request.userId());
                return new OrderStatusResponse(
                        order.getId(),
                        order.getStatus(),
                        "Order placed on " + order.getCreatedAt() + " with " +
                        order.getItems().size() + " items. Total: ₹" + order.getTotalAmount()
                );
            } catch (Exception e) {
                return new OrderStatusResponse(null, "UNKNOWN",
                        "Could not retrieve order status. Please try again later.");
            }
        };
    }
}
```

---

### 6. Function Calling — `InventoryCheckFunction.java`

```java
package com.onlineshopping.ai_service.function;

import com.onlineshopping.ai_service.client.ProductClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

@Configuration
public class InventoryCheckFunction {

    private final ProductClient productClient;

    public InventoryCheckFunction(ProductClient productClient) {
        this.productClient = productClient;
    }

    public record InventoryRequest(Long productId, String productName) {}
    public record InventoryResponse(Long productId, String productName, boolean inStock, int quantity) {}

    @Bean
    @Description("Check if a specific product is currently in stock and how many units are available. " +
                 "Use this when the user asks about product availability or stock.")
    public Function<InventoryRequest, InventoryResponse> checkInventory() {
        return request -> {
            try {
                var product = productClient.getProductById(request.productId());
                boolean inStock = product.getStock() != null && product.getStock() > 0;
                return new InventoryResponse(
                        product.getId(),
                        product.getName(),
                        inStock,
                        product.getStock() != null ? product.getStock() : 0
                );
            } catch (Exception e) {
                return new InventoryResponse(request.productId(), request.productName(), false, 0);
            }
        };
    }
}
```

---

### 7. Semantic Product Search — `EmbeddingService.java`

```java
package com.onlineshopping.ai_service.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Semantic Search using Spring AI Vector Store.
 * 
 * HOW IT WORKS:
 *   1. Product catalog is embedded into vectors (high-dimensional number arrays)
 *   2. Each product becomes a "document" in PGVector with metadata
 *   3. User query "comfortable shoes for running" → embedded into a vector
 *   4. Vector similarity search (cosine) finds closest product vectors
 *   5. Returns products that are SEMANTICALLY similar — even if keywords don't match
 * 
 * Example:
 *   Query: "something to keep my coffee hot"
 *   Result: "Insulated Travel Mug - Stainless Steel Thermos"
 *   (Traditional keyword search would MISS this — no "hot" or "coffee" in product name)
 */
@Service
public class EmbeddingService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public EmbeddingService(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Store a product as an embedded document in the vector store.
     * Called when a product is created or updated (via Kafka event).
     */
    public void embedProduct(Long productId, String name, String description,
                             String category, Double price) {
        String content = String.format(
            "Product: %s. Description: %s. Category: %s. Price: ₹%.2f",
            name, description, category, price
        );

        Document document = new Document(content, Map.of(
                "productId", productId.toString(),
                "name", name,
                "category", category,
                "price", price.toString()
        ));

        vectorStore.add(List.of(document));
    }

    /**
     * Semantic search — find products by meaning.
     * Returns top-K most similar products to the query.
     */
    public List<Document> semanticSearch(String query, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(0.7)  // Only return results with >70% similarity
                        .build()
        );
    }

    /**
     * Find similar products (recommendations).
     * Embeds the source product and finds nearest neighbors.
     */
    public List<Document> findSimilarProducts(Long productId, String productDescription, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(productDescription)
                        .topK(topK + 1)  // +1 because source product will match itself
                        .filterExpression("productId != '" + productId + "'")
                        .build()
        );
    }
}
```

---

### 8. Recommendation Controller

```java
package com.onlineshopping.ai_service.controller;

import com.onlineshopping.ai_service.dto.RecommendationResponse;
import com.onlineshopping.ai_service.dto.SemanticSearchResponse;
import com.onlineshopping.ai_service.service.EmbeddingService;
import com.onlineshopping.ai_service.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/ai")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final EmbeddingService embeddingService;

    public RecommendationController(RecommendationService recommendationService,
                                    EmbeddingService embeddingService) {
        this.recommendationService = recommendationService;
        this.embeddingService = embeddingService;
    }

    /**
     * GET /ai/recommendations/{productId}
     * Returns products similar to the given product (vector similarity)
     */
    @GetMapping("/recommendations/{productId}")
    public ResponseEntity<List<RecommendationResponse>> getRecommendations(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "5") int limit) {
        return ResponseEntity.ok(recommendationService.getSimilarProducts(productId, limit));
    }

    /**
     * GET /ai/search?q=...
     * Semantic search — finds products by meaning, not just keywords
     */
    @GetMapping("/search")
    public ResponseEntity<List<SemanticSearchResponse>> semanticSearch(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") int limit) {
        var results = embeddingService.semanticSearch(query, limit);
        var response = results.stream()
                .map(doc -> SemanticSearchResponse.builder()
                        .productId(Long.parseLong(doc.getMetadata().get("productId").toString()))
                        .name(doc.getMetadata().get("name").toString())
                        .category(doc.getMetadata().get("category").toString())
                        .price(Double.parseDouble(doc.getMetadata().get("price").toString()))
                        .relevanceScore(doc.getScore() != null ? doc.getScore() : 0.0)
                        .build())
                .toList();
        return ResponseEntity.ok(response);
    }
}
```

---

### 9. Content Generation — `ContentGenerationService.java`

```java
package com.onlineshopping.ai_service.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * AI-powered content generation for the platform.
 * 
 * Use cases:
 *   - Generate SEO product descriptions from basic info
 *   - Generate personalized email content for notifications
 *   - Summarize product reviews
 */
@Service
public class ContentGenerationService {

    private final ChatClient chatClient;

    public ContentGenerationService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Generate a product description from basic product info.
     * Used by Product Service when admin creates a product without detailed description.
     */
    public String generateProductDescription(String productName, String category, Double price) {
        return chatClient.prompt()
                .system("""
                    You are an e-commerce copywriter. Generate a compelling, SEO-friendly product
                    description in 2-3 sentences. Be specific and highlight key features.
                    Do not use markdown formatting. Keep it under 200 characters.
                    """)
                .user(String.format(
                    "Generate a product description for: %s in category '%s' priced at ₹%.2f",
                    productName, category, price))
                .call()
                .content();
    }

    /**
     * Generate personalized notification email content.
     * Called by Notification Service via Feign to create AI-crafted emails.
     */
    public String generateNotificationContent(String status, String userName,
                                              String orderId, Double totalAmount) {
        String prompt = String.format("""
            Generate a short, friendly email body for an order status update:
            - Customer name: %s
            - Order ID: %s
            - Status: %s
            - Total amount: ₹%.2f
            
            Keep it under 100 words. Be warm and professional. 
            Include a relevant emoji. Don't include subject line.
            """, userName, orderId, status, totalAmount);

        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }

    /**
     * Summarize product reviews into pros/cons.
     * Future use — when review system is added.
     */
    public String summarizeReviews(String productName, String reviews) {
        return chatClient.prompt()
                .system("Summarize the following product reviews into bullet-point pros and cons. Be concise.")
                .user(String.format("Product: %s\n\nReviews:\n%s", productName, reviews))
                .call()
                .content();
    }
}
```

---

### 10. Content Generation Controller

```java
package com.onlineshopping.ai_service.controller;

import com.onlineshopping.ai_service.dto.GenerateDescriptionRequest;
import com.onlineshopping.ai_service.service.ContentGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ai")
public class ContentController {

    private final ContentGenerationService contentService;

    public ContentController(ContentGenerationService contentService) {
        this.contentService = contentService;
    }

    /**
     * POST /ai/generate/description — Generate AI product description
     * Called by Product Service when admin wants AI-generated description
     */
    @PostMapping("/generate/description")
    public ResponseEntity<Map<String, String>> generateDescription(
            @RequestBody GenerateDescriptionRequest request) {
        String description = contentService.generateProductDescription(
                request.getProductName(), request.getCategory(), request.getPrice());
        return ResponseEntity.ok(Map.of("description", description));
    }

    /**
     * POST /ai/generate/notification — Generate personalized notification text
     * Called by Notification Service to craft AI-powered email content
     */
    @PostMapping("/generate/notification")
    public ResponseEntity<Map<String, String>> generateNotification(
            @RequestBody Map<String, Object> request) {
        String content = contentService.generateNotificationContent(
                (String) request.get("status"),
                (String) request.get("userName"),
                (String) request.get("orderId"),
                Double.parseDouble(request.get("totalAmount").toString())
        );
        return ResponseEntity.ok(Map.of("content", content));
    }
}
```

---

### 11. Kafka Consumer — Product Sync to Vector Store

```java
package com.onlineshopping.ai_service.kafka;

import com.onlineshopping.ai_service.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes product-events from Product Service.
 * When a product is CREATED or UPDATED, re-embed it into the vector store.
 * This keeps semantic search and recommendations up-to-date.
 */
@Component
@Slf4j
public class ProductEventConsumer {

    private final EmbeddingService embeddingService;

    public ProductEventConsumer(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @KafkaListener(
        topics = "product-events",
        groupId = "ai-service-embedding-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeProductEvent(ConsumerRecord<String, Map<String, Object>> record,
                                    Acknowledgment ack) {
        Map<String, Object> event = record.value();
        String eventType = (String) event.get("eventType");  // CREATED, UPDATED, DELETED

        log.info("[KAFKA] Product event: type={}, productId={}", eventType, event.get("productId"));

        try {
            if ("DELETED".equals(eventType)) {
                // Remove from vector store — handled by PGVector DELETE
                log.info("Product deleted — remove from vector store: {}", event.get("productId"));
            } else {
                // Embed/re-embed product
                embeddingService.embedProduct(
                        Long.parseLong(event.get("productId").toString()),
                        (String) event.get("name"),
                        (String) event.get("description"),
                        (String) event.get("category"),
                        Double.parseDouble(event.get("price").toString())
                );
                log.info("Product embedded successfully: {}", event.get("productId"));
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process product event: {}", e.getMessage());
            // Don't ack — Kafka will redeliver
        }
    }
}
```

---

### 12. Vector Store Configuration

```java
package com.onlineshopping.ai_service.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    /**
     * PGVector Store — stores product embeddings in PostgreSQL.
     * 
     * Why PGVector over Redis Vector?
     *   - Persistent (survives restarts)
     *   - SQL filtering on metadata (price range, category)
     *   - HNSW index for fast approximate nearest neighbor search
     *   - Already using PostgreSQL — no extra infrastructure
     */
    @Bean
    public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1536)                    // OpenAI text-embedding-3-small dimension
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)              // Auto-create table + extension
                .schemaName("public")
                .tableName("product_embeddings")
                .build();
    }
}
```

---

## 🛡️ Security — Same Pattern as Other Services

```java
package com.onlineshopping.ai_service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayAuthFilter gatewayAuthFilter;

    public SecurityConfig(GatewayAuthFilter gatewayAuthFilter) {
        this.gatewayAuthFilter = gatewayAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public: semantic search, recommendations
                .requestMatchers(HttpMethod.GET, "/ai/search/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/ai/recommendations/**").permitAll()
                // Authenticated: chat, content generation
                .requestMatchers("/ai/chat/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/ai/generate/**").hasRole("ADMIN")
                // Actuator
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

---

## ⚙️ Configuration — `application.properties`

```properties
# ─────────────────────────────────────────
# APPLICATION
# ─────────────────────────────────────────
spring.application.name=AI-SERVICE
server.port=8086

# ─────────────────────────────────────────
# EUREKA
# ─────────────────────────────────────────
eureka.client.service-url.defaultZone=http://localhost:8761/eureka
eureka.client.register-with-eureka=true
eureka.client.fetch-registry=true
eureka.instance.prefer-ip-address=true

# ─────────────────────────────────────────
# SPRING AI — OpenAI (Production)
# ─────────────────────────────────────────
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o-mini
spring.ai.openai.chat.options.temperature=0.7
spring.ai.openai.embedding.options.model=text-embedding-3-small

# ─────────────────────────────────────────
# SPRING AI — Ollama (Local Development)
# Uncomment below and comment OpenAI section for local dev
# ─────────────────────────────────────────
# spring.ai.ollama.base-url=http://localhost:11434
# spring.ai.ollama.chat.options.model=llama3.1
# spring.ai.ollama.embedding.options.model=nomic-embed-text

# ─────────────────────────────────────────
# POSTGRESQL + PGVECTOR (Vector Store)
# ─────────────────────────────────────────
spring.datasource.url=jdbc:postgresql://localhost:5432/ai_db
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.driver-class-name=org.postgresql.Driver
spring.jpa.hibernate.ddl-auto=update

# ─────────────────────────────────────────
# REDIS (Conversation memory cache)
# ─────────────────────────────────────────
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=2000ms

# ─────────────────────────────────────────
# KAFKA (Consume product-events for embedding sync)
# ─────────────────────────────────────────
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.consumer.group-id=ai-service-embedding-group
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer
spring.kafka.consumer.properties.spring.json.trusted.packages=*

# ─────────────────────────────────────────
# ACTUATOR
# ─────────────────────────────────────────
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.show-details=always

# ─────────────────────────────────────────
# LOGGING
# ─────────────────────────────────────────
logging.level.com.onlineshopping=DEBUG
logging.level.org.springframework.ai=DEBUG
```

---

## 📦 Dependencies — `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
    </parent>

    <groupId>com.onlineshopping</groupId>
    <artifactId>ai-service</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>ai-service</name>
    <description>AI-powered features for Online Shopping — Spring AI</description>

    <properties>
        <java.version>21</java.version>
        <spring-cloud.version>2023.0.2</spring-cloud.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencies>

        <!-- ═══════════════════════════════════════════════════════════
             SPRING AI — Core
             ═══════════════════════════════════════════════════════════ -->
        
        <!-- Spring AI OpenAI (Chat + Embeddings) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        </dependency>

        <!-- Spring AI Ollama (Local development alternative) -->
        <!-- Uncomment for local dev without OpenAI API key -->
        <!--
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
        </dependency>
        -->

        <!-- Spring AI PGVector (Vector Store for semantic search) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
        </dependency>

        <!-- ═══════════════════════════════════════════════════════════
             SPRING BOOT — Standard
             ═══════════════════════════════════════════════════════════ -->

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- PostgreSQL Driver -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Kafka -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Security -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- Eureka Client -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- OpenFeign (call other services) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>

        <!-- Actuator -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <name>Spring Milestones</name>
            <url>https://repo.spring.io/milestone</url>
            <snapshots><enabled>false</enabled></snapshots>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

---

## ⚙️ API Gateway Routes — Add to `FilterConfig.java`

```java
// Route: GET /api/ai/search, /api/ai/recommendations → PUBLIC (no JWT)
.route("ai-service-public", r -> r
        .path("/api/ai/search/**", "/api/ai/recommendations/**")
        .and().method("GET")
        .filters(f -> f.rewritePath("/api/ai/(?<segment>.*)", "/ai/${segment}"))
        .uri("lb://AI-SERVICE"))

// Route: POST /api/ai/chat, /api/ai/generate → JWT Required
.route("ai-service-protected", r -> r
        .path("/api/ai/**")
        .and().method("POST", "PUT", "DELETE")
        .filters(f -> f
                .filter(jwtAuthFilter)
                .rewritePath("/api/ai/(?<segment>.*)", "/ai/${segment}"))
        .uri("lb://AI-SERVICE"))
```

**Or in `application.properties`:**
```properties
# ================= AI SERVICE (Public — search/recommendations) =================
spring.cloud.gateway.routes[6].id=ai-service-public
spring.cloud.gateway.routes[6].uri=lb://AI-SERVICE
spring.cloud.gateway.routes[6].predicates[0]=Path=/api/ai/search/**,/api/ai/recommendations/**
spring.cloud.gateway.routes[6].predicates[1]=Method=GET
spring.cloud.gateway.routes[6].filters[0]=RewritePath=/api/ai/(?<segment>.*), /ai/${segment}

# ================= AI SERVICE (Protected — chat/generate) =================
spring.cloud.gateway.routes[7].id=ai-service-protected
spring.cloud.gateway.routes[7].uri=lb://AI-SERVICE
spring.cloud.gateway.routes[7].predicates[0]=Path=/api/ai/**
spring.cloud.gateway.routes[7].predicates[1]=Method=POST,PUT,DELETE
spring.cloud.gateway.routes[7].filters[0]=RewritePath=/api/ai/(?<segment>.*), /ai/${segment}
```

---

## 🔗 Integration with Existing Services

### Product Service → AI Service (Optional Enhancement)

Add Feign client to Product Service for AI-generated descriptions:

```java
// product-service/client/AiClient.java
@FeignClient(name = "AI-SERVICE")
public interface AiClient {
    
    @PostMapping("/ai/generate/description")
    Map<String, String> generateDescription(@RequestBody Map<String, Object> request);
}
```

**Usage in Product Service:**
```java
// ProductServiceImpl.java — when admin doesn't provide a description
@Override
public void createProduct(CreateProduct dto) {
    if (dto.getDescription() == null || dto.getDescription().isBlank()) {
        // Auto-generate AI description
        Map<String, Object> aiRequest = Map.of(
            "productName", dto.getName(),
            "category", dto.getCategoryName(),
            "price", dto.getPrice()
        );
        Map<String, String> aiResponse = aiClient.generateDescription(aiRequest);
        dto.setDescription(aiResponse.get("description"));
    }
    // ... continue saving product
}
```

---

### Notification Service → AI Service (Optional Enhancement)

Add Feign client to Notification Service for AI-crafted emails:

```java
// notification-service/client/AiClient.java
@FeignClient(name = "AI-SERVICE")
public interface AiClient {

    @PostMapping("/ai/generate/notification")
    Map<String, String> generateNotificationContent(@RequestBody Map<String, Object> request);
}
```

**Usage in Notification Service:**
```java
// NotificationServiceImpl.java — before sending email
private String getEmailBody(OrderStatusEvent event) {
    try {
        Map<String, Object> aiRequest = Map.of(
            "status", event.getStatus(),
            "userName", event.getUserEmail().split("@")[0],
            "orderId", event.getOrderId(),
            "totalAmount", event.getTotalAmount()
        );
        Map<String, String> response = aiClient.generateNotificationContent(aiRequest);
        return response.get("content");
    } catch (Exception e) {
        // Fallback to static template if AI service is unavailable
        return getStaticEmailBody(event);
    }
}
```

---

### Product Service → Publish Events to Kafka (Required for Vector Sync)

**Add to Product Service** — publish events when products are created/updated/deleted:

```java
// product-service/kafka/ProductEventProducer.java
@Component
public class ProductEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ProductEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishProductEvent(String eventType, Long productId, 
                                    String name, String description,
                                    String category, Double price) {
        Map<String, Object> event = Map.of(
            "eventType", eventType,       // CREATED, UPDATED, DELETED
            "productId", productId,
            "name", name,
            "description", description != null ? description : "",
            "category", category != null ? category : "",
            "price", price
        );
        kafkaTemplate.send("product-events", productId.toString(), event);
    }
}
```

**Add Kafka topic to Product Service `application.properties`:**
```properties
# Kafka Producer (for AI Service vector sync)
spring.kafka.bootstrap-servers=localhost:9092
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer
```

---

## 🧪 Postman Test Endpoints

**Base URL (via Gateway):** `http://localhost:8080`  
**Direct URL:** `http://localhost:8086`

### 🤖 Chat (Requires JWT)

| Method | URL | Auth | Body |
|--------|-----|------|------|
| `POST` | `/api/ai/chat` | Bearer | `{"message":"Show me laptops under 50000","sessionId":"session-1"}` |
| `POST` | `/api/ai/chat` | Bearer | `{"message":"Is product #5 in stock?","sessionId":"session-1"}` |
| `POST` | `/api/ai/chat` | Bearer | `{"message":"What's my order status?","sessionId":"session-1"}` |
| `POST` | `/api/ai/chat/stream` | Bearer | `{"message":"Recommend a good phone","sessionId":"session-1"}` |

### 🔍 Semantic Search (Public)

| Method | URL | Auth | Body |
|--------|-----|------|------|
| `GET` | `/api/ai/search?q=comfortable running shoes` | None | — |
| `GET` | `/api/ai/search?q=something to keep coffee hot&limit=5` | None | — |
| `GET` | `/api/ai/search?q=gaming laptop high performance` | None | — |

### 💡 Recommendations (Public)

| Method | URL | Auth | Body |
|--------|-----|------|------|
| `GET` | `/api/ai/recommendations/1?limit=5` | None | — |
| `GET` | `/api/ai/recommendations/3?limit=10` | None | — |

### ✍️ Content Generation (Admin Only)

| Method | URL | Auth | Body |
|--------|-----|------|------|
| `POST` | `/api/ai/generate/description` | Bearer ADMIN | `{"productName":"Wireless Earbuds","category":"Electronics","price":2999.0}` |
| `POST` | `/api/ai/generate/notification` | Bearer ADMIN | `{"status":"CONFIRMED","userName":"John","orderId":"abc123","totalAmount":4999.0}` |

---

## 🚀 Build & Run

### Prerequisites

1. **Java 21+**
2. **Maven 3.9+**
3. **PostgreSQL 15+** with pgvector extension:
   ```sql
   CREATE DATABASE ai_db;
   \c ai_db
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
4. **Redis** on `localhost:6379`
5. **Kafka** on `localhost:9092`
6. **Eureka Server** on `localhost:8761`
7. **OpenAI API Key** (or Ollama for local dev)

### Environment Variables

```bash
# Required
export OPENAI_API_KEY=sk-your-key-here

# OR for local dev with Ollama (free, no API key needed):
# 1. Install Ollama: https://ollama.ai
# 2. Pull models:
ollama pull llama3.1
ollama pull nomic-embed-text
# 3. Switch to Ollama config in application.properties
```

### Create Spring Boot Project

- **Artifact:** `ai-service`
- **Group:** `com.onlineshopping`
- **Package:** `com.onlineshopping.ai_service`
- **Spring Boot:** `3.3.x`
- **Java:** `21`

### Run
```bash
./mvnw spring-boot:run
```

### Startup Order (Updated)
```
1. MySQL + Redis + Kafka + Zookeeper + PostgreSQL
2. Eureka Server       (port 8761)
3. User Service        (port 8081)
4. Product Service     (port 8082)
5. Order Service       (port 8083)
6. Inventory Service   (port 8084)
7. Notification Service (port 8085)
8. AI Service          (port 8086)  ← NEW
9. API Gateway         (port 8080)
```

---

## 🔄 Event Flow — Product Sync to Vector Store

```
┌──────────────────────────────────────────────────────────────────────────┐
│              PRODUCT → VECTOR STORE SYNC (via Kafka)                      │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  Admin creates product via POST /api/products                            │
│       │                                                                  │
│       ▼                                                                  │
│  Product Service                                                         │
│       │  save to MySQL                                                   │
│       │  publish to Kafka topic: product-events                          │
│       ▼                                                                  │
│  Kafka: product-events                                                   │
│       │                                                                  │
│       │  consumed by AI Service                                          │
│       ▼                                                                  │
│  AI Service → ProductEventConsumer                                       │
│       │                                                                  │
│       │  1. Extract product name + description + category + price        │
│       │  2. Combine into a single text: "Product: X. Description: Y..."  │
│       │  3. Call EmbeddingModel.embed(text) → [0.023, -0.156, ...]      │
│       │  4. Store vector + metadata in PGVector                          │
│       ▼                                                                  │
│  PostgreSQL (ai_db) → product_embeddings table                           │
│       │                                                                  │
│       │  Now available for:                                              │
│       │    - Semantic search: GET /ai/search?q=...                       │
│       │    - Recommendations: GET /ai/recommendations/{id}               │
│       │    - Chatbot RAG context                                         │
│       ▼                                                                  │
│  User asks: "Show me something for gaming"                               │
│       │                                                                  │
│       │  1. Embed query → vector                                         │
│       │  2. Cosine similarity search in PGVector                         │
│       │  3. Return top-K products                                        │
│       ▼                                                                  │
│  Response: [{Gaming Laptop, 0.94}, {Gaming Mouse, 0.87}, ...]           │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 🧠 Spring AI Concepts Used

| Concept | Where Used | What It Does |
|---------|-----------|--------------|
| **ChatClient** | `ChatService`, `ContentGenerationService` | Primary interface for LLM conversations |
| **Function Calling** | `ProductSearchFunction`, `OrderStatusFunction`, `InventoryCheckFunction` | LLM can call your Java methods to get real-time data |
| **EmbeddingModel** | `EmbeddingService` | Converts text to high-dimensional vectors |
| **VectorStore (PGVector)** | `VectorStoreConfig`, `EmbeddingService` | Stores & searches product vectors by semantic similarity |
| **Chat Memory** | `AiConfig` (MessageChatMemoryAdvisor) | Maintains conversation history across messages |
| **Prompt Templates** | `resources/prompts/*.st` | Reusable prompt templates with variable substitution |
| **Structured Output** | Future: review summarization | Parse LLM output into Java objects |
| **Streaming** | `ChatController.chatStream()` | Token-by-token streaming for real-time UI |

---

## ✅ Implementation Checklist

### 🔴 Must Build (Core AI Service)
- [ ] `AiServiceApplication.java` with `@EnableDiscoveryClient` + `@EnableFeignClients`
- [ ] `AiConfig.java` — ChatClient bean with system prompt
- [ ] `VectorStoreConfig.java` — PGVector store setup
- [ ] `ChatController.java` + `ChatService.java` — Chatbot endpoint
- [ ] `ProductSearchFunction.java` — Function calling for product search
- [ ] `OrderStatusFunction.java` — Function calling for order status
- [ ] `InventoryCheckFunction.java` — Function calling for stock check
- [ ] `EmbeddingService.java` — Embed products + semantic search
- [ ] `RecommendationController.java` — Similar products endpoint
- [ ] `SearchController.java` — Semantic search endpoint
- [ ] `GatewayAuthFilter.java` + `SecurityConfig.java`
- [ ] `application.properties` with all configs
- [ ] `pom.xml` with Spring AI BOM

### 🔴 Must Update (Other Services)
- [ ] **API Gateway** — Add AI Service routes (public + protected)
- [ ] **Product Service** — Add Kafka producer for `product-events` topic
- [ ] **Product Service** — Add `spring-kafka` dependency + `KafkaTemplate` bean

### 🟡 Should Build (Enhancements)
- [ ] `ContentGenerationService.java` — AI product descriptions
- [ ] `ProductEventConsumer.java` — Kafka sync to vector store
- [ ] Product Service → AI Service Feign client (auto-generate descriptions)
- [ ] Notification Service → AI Service Feign client (AI emails)
- [ ] Redis-backed `ChatMemory` (instead of in-memory) for persistence

### 🟢 Nice to Have (Future)
- [ ] Review summarization endpoint
- [ ] Fraud detection via structured output
- [ ] Multi-modal: image-based product search
- [ ] RAG with store policies PDF

---

## 📊 Updated Service Matrix

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                    COMPLETE SERVICE MATRIX (with AI Service)                          │
├─────────────────┬──────┬────────┬──────┬────────┬──────────┬───────────┬────────────┤
│ Service         │ Port │ DB     │ Redis│ Kafka  │ Eureka   │ AI        │ Gateway    │
├─────────────────┼──────┼────────┼──────┼────────┼──────────┼───────────┼────────────┤
│ API Gateway     │ 8080 │ —      │ ✅   │ —      │ ✅ Client│ —         │ —          │
│ User Service    │ 8081 │ MySQL  │ ✅   │ —      │ ✅       │ —         │ /api/auth  │
│ Product Service │ 8082 │ MySQL  │ ✅   │ Producer│ ✅      │ Feign→AI  │ /api/prod  │
│ Order Service   │ 8083 │ MongoDB│ ✅   │ Producer│ ✅      │ —         │ /api/orders│
│ Inventory Svc   │ 8084 │ MySQL  │ ✅   │ Both   │ ✅       │ —         │ /api/inv   │
│ Notification    │ 8085 │ MySQL  │ —    │ Consumer│ ✅      │ Feign→AI  │ /api/notif │
│ AI Service      │ 8086 │ PgSQL  │ ✅   │ Consumer│ ✅      │ Self      │ /api/ai    │
└─────────────────┴──────┴────────┴──────┴────────┴──────────┴───────────┴────────────┘
```

---

## 🐳 Docker Compose Addition

```yaml
  # Add to existing docker-compose.yml
  
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: ai_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - pgvector_data:/var/lib/postgresql/data

  # Optional: Ollama for local dev (free, no API key)
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    # After startup, run: docker exec ollama ollama pull llama3.1
    # And: docker exec ollama ollama pull nomic-embed-text

volumes:
  pgvector_data:
  ollama_data:
```

---

## 💡 Why Spring AI Over Direct API Calls?

| Feature | Direct OpenAI API | Spring AI |
|---------|-------------------|-----------|
| **Model switching** | Rewrite client code | Change 1 property (`spring.ai.openai` → `spring.ai.ollama`) |
| **Function calling** | Manual JSON schema | `@Bean Function<Request, Response>` + `@Description` |
| **Vector store** | Manual pgvector SQL | `vectorStore.add()` / `vectorStore.similaritySearch()` |
| **Chat memory** | Manual history management | `MessageChatMemoryAdvisor` — automatic |
| **Streaming** | Manual SSE handling | `chatClient.stream().content()` → `Flux<String>` |
| **Structured output** | Manual JSON parsing | `chatClient.call().entity(MyClass.class)` |
| **Retry/fallback** | Manual implementation | Built-in with Spring Retry |
| **Observability** | Manual logging | Micrometer metrics + tracing built-in |
| **Testing** | Mock HTTP | `@MockBean EmbeddingModel` — standard Spring testing |

---

## 🏗️ Local Dev Without OpenAI (Free — Ollama)

For development without paying for API calls:

```bash
# 1. Install Ollama (https://ollama.ai)
# 2. Pull models
ollama pull llama3.1            # Chat model (~4GB)
ollama pull nomic-embed-text    # Embedding model (~250MB)

# 3. Switch application.properties to Ollama profile
# Comment out spring.ai.openai.* 
# Uncomment spring.ai.ollama.*
```

**Profile-based config (recommended):**

`application-dev.properties`:
```properties
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=llama3.1
spring.ai.ollama.embedding.options.model=nomic-embed-text
```

`application-prod.properties`:
```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o-mini
spring.ai.openai.embedding.options.model=text-embedding-3-small
```

Run with profile:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev    # Ollama (free)
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod   # OpenAI (paid)
```

