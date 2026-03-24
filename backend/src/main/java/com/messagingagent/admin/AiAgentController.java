package com.messagingagent.admin;

import com.messagingagent.model.AiAgentConfig;
import com.messagingagent.model.AiChatMessage;
import com.messagingagent.repository.AiAgentConfigRepository;
import com.messagingagent.repository.AiChatMessageRepository;
import com.messagingagent.service.AiChatService;
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
    private final AiChatMessageRepository chatMessageRepository;

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

    /**
     * Chat with the AI agent. Accepts conversation history and returns the assistant's reply.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chat(@RequestBody Map<String, Object> request) {
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

        try {
            String reply = chatService.chat(config, messages);

            // Persist the user message and assistant reply
            Map<String, String> lastUserMsg = messages.get(messages.size() - 1);
            chatMessageRepository.save(AiChatMessage.builder()
                    .role(lastUserMsg.get("role")).content(lastUserMsg.get("content")).build());
            chatMessageRepository.save(AiChatMessage.builder()
                    .role("assistant").content(reply).build());

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

    /** Load persisted chat history */
    @GetMapping("/history")
    public List<AiChatMessage> getHistory() {
        return chatMessageRepository.findAllByOrderByCreatedAtAsc();
    }

    /** Clear chat history */
    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory() {
        chatMessageRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}
