package com.messagingagent.admin;

import com.messagingagent.model.AiAgentConfig;
import com.messagingagent.model.AiChatMessage;
import com.messagingagent.model.AiChatSession;
import com.messagingagent.model.AiMemory;
import com.messagingagent.repository.AiAgentConfigRepository;
import com.messagingagent.repository.AiChatMessageRepository;
import com.messagingagent.repository.AiChatSessionRepository;
import com.messagingagent.repository.AiMemoryRepository;
import com.messagingagent.service.AiChatService;
import com.messagingagent.service.AiMemoryService;
import com.messagingagent.service.PlatformHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent configuration and chat proxy.
 */
@RestController
@RequestMapping("/api/ai-agent")
@RequiredArgsConstructor
@Slf4j
public class AiAgentController {

    private final AiAgentConfigRepository configRepository;
    private final PlatformHealthService healthService;
    private final AiChatService chatService;
    private final AiMemoryService memoryService;
    private final AiChatMessageRepository chatMessageRepository;
    private final AiMemoryRepository memoryRepository;
    private final AiChatSessionRepository chatSessionRepository;

    @GetMapping("/config")
    public AiAgentConfig getConfig() {
        return configRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No AI config found"));
    }

    @PutMapping("/config")
    public AiAgentConfig updateConfig(@RequestBody AiAgentConfig updates) {
        AiAgentConfig existing = configRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No AI config found"));
        existing.setProvider(updates.getProvider());
        existing.setModelName(updates.getModelName());
        existing.setEnabled(updates.isEnabled());
        if (updates.getApiKey() != null && !updates.getApiKey().isBlank()) {
            existing.setApiKey(updates.getApiKey());
        }
        log.info("Updated AI agent config: provider={}, model={}, enabled={}",
                existing.getProvider(), existing.getModelName(), existing.isEnabled());
        return configRepository.save(existing);
    }

    /**
     * Returns system context that the AI agent can use
     * (platform health, fleet status, pipeline stats).
     */
    @GetMapping("/context")
    public Map<String, Object> getAgentContext() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("platformHealth", healthService.getFullHealth());
        ctx.put("deliveryRate1h", healthService.getDeliveryRate(60));
        ctx.put("queuedMessages", healthService.getQueuedCount());
        ctx.put("offlineDevices", healthService.getOfflineDeviceCount());
        return ctx;
    }

    /** Create a new blank session */
    @PostMapping("/sessions")
    public AiChatSession createSession() {
        return chatSessionRepository.save(AiChatSession.builder()
                .title("New Chat Instance " + java.time.LocalDate.now())
                .build());
    }

    /** Load persisted chat sessions */
    @GetMapping("/sessions")
    public List<AiChatSession> getSessions() {
        return chatSessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    /** Delete a session */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        chatSessionRepository.deleteById(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Chat with the AI agent. Accepts conversation history and returns the assistant's reply.
     */
    @PostMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, String>> chat(@PathVariable Long sessionId, @RequestBody Map<String, Object> request) {
        AiAgentConfig config = configRepository.findAll().stream().findFirst().orElse(null);
        if (config == null || !config.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("error", "AI Agent is not enabled"));
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No API key configured"));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No messages provided"));
        }
        
        AiChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        try {
            String reply = chatService.chat(config, messages);

            // Persist the user message and assistant reply
            Map<String, String> lastUserMsg = messages.get(messages.size() - 1);
            chatMessageRepository.save(AiChatMessage.builder()
                    .sessionId(sessionId)
                    .role(lastUserMsg.get("role")).content(lastUserMsg.get("content")).build());
            chatMessageRepository.save(AiChatMessage.builder()
                    .sessionId(sessionId)
                    .role("assistant").content(reply).build());
            
            // Update session timestamp
            session.setUpdatedAt(java.time.Instant.now());
            
            // Auto-title if it's the first real message
            if (session.getTitle().startsWith("New Chat Instance") && lastUserMsg.get("content").length() > 5) {
                String snippet = lastUserMsg.get("content");
                if (snippet.length() > 30) snippet = snippet.substring(0, 30) + "...";
                session.setTitle("Chat: " + snippet);
            }
            chatSessionRepository.save(session);

            // Build full history to send to memory extraction
            List<Map<String, String>> fullHistoryForMemory = new java.util.ArrayList<>(messages);
            fullHistoryForMemory.add(Map.of("role", "assistant", "content", reply));
            
            // Asynchronously extract and save memories
            memoryService.extractAndStoreMemories(config, fullHistoryForMemory);

            return ResponseEntity.ok(Map.of("reply", reply));
        } catch (Exception e) {
            log.error("AI chat error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    /**
     * Test connection to the configured AI provider.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> testConnection() {
        AiAgentConfig config = configRepository.findAll().stream().findFirst().orElse(null);
        if (config == null || config.getApiKey() == null || config.getApiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "No API key configured"));
        }

        // Test by sending a short message to verify the API key works
        try {
            List<Map<String, String>> testMsg = List.of(Map.of("role", "user", "content", "Reply with just 'OK' to confirm you are working."));
            String reply = chatService.chat(config, testMsg);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "provider", config.getProvider().name(),
                    "model", config.getModelName(),
                    "message", "Connection successful. Response: " + reply.substring(0, Math.min(reply.length(), 100))
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "status", "ERROR",
                    "provider", config.getProvider().name(),
                    "model", config.getModelName(),
                    "message", "Connection failed: " + e.getMessage()
            ));
        }
    }

    /** Load persisted chat history for a session */
    @GetMapping("/history/{sessionId}")
    public List<AiChatMessage> getHistory(@PathVariable Long sessionId) {
        return chatMessageRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /** Load persisted memories */
    @GetMapping("/memories")
    public List<AiMemory> getMemories() {
        return memoryRepository.findAll();
    }

    /** Clear a specific memory */
    @DeleteMapping("/memories/{id}")
    public ResponseEntity<Void> deleteMemory(@PathVariable Long id) {
        memoryRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Clear all memories */
    @DeleteMapping("/memories")
    public ResponseEntity<Void> clearMemories() {
        memoryRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
