package com.messagingagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.AiAgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Proxies chat messages to the configured LLM provider (Gemini / Claude / OpenAI).
 * Automatically injects real-time system context so the AI can reason
 * about platform health, device fleet status, and pipeline metrics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiChatService {

    private final PlatformHealthService healthService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Sends a user message to the configured AI provider, prepending system context.
     * Returns the assistant's reply as a string.
     */
    public String chat(AiAgentConfig config, List<Map<String, String>> conversationHistory) throws Exception {
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            throw new IllegalStateException("API key is not configured");
        }

        String systemContext = buildSystemContext();

        return switch (config.getProvider()) {
            case GEMINI -> callGemini(config, systemContext, conversationHistory);
            case CLAUDE -> callClaude(config, systemContext, conversationHistory);
            case OPENAI -> callOpenAI(config, systemContext, conversationHistory);
        };
    }

    // ── Gemini ─────────────────────────────────────────────────────────────

    private String callGemini(AiAgentConfig config, String systemContext,
                              List<Map<String, String>> history) throws Exception {
        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                config.getModelName(), config.getApiKey());

        // Build Gemini request body
        Map<String, Object> body = new LinkedHashMap<>();

        // System instruction
        body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemContext))
        ));

        // Conversation contents
        List<Map<String, Object>> contents = new ArrayList<>();
        for (Map<String, String> msg : history) {
            String role = "user".equals(msg.get("role")) ? "user" : "model";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", msg.get("content")))));
        }
        body.put("contents", contents);

        // Generation config
        body.put("generationConfig", Map.of(
                "temperature", 0.7,
                "maxOutputTokens", 4096
        ));

        String responseBody = post(url, objectMapper.writeValueAsString(body), Map.of("Content-Type", "application/json"));
        JsonNode root = objectMapper.readTree(responseBody);

        // Check for error
        if (root.has("error")) {
            String errorMsg = root.path("error").path("message").asText("Unknown Gemini error");
            throw new RuntimeException("Gemini API error: " + errorMsg);
        }

        return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
    }

    // ── Claude ─────────────────────────────────────────────────────────────

    private String callClaude(AiAgentConfig config, String systemContext,
                              List<Map<String, String>> history) throws Exception {
        String url = "https://api.anthropic.com/v1/messages";

        // Build messages (Claude uses "user"/"assistant" roles)
        List<Map<String, String>> messages = new ArrayList<>();
        for (Map<String, String> msg : history) {
            messages.add(Map.of(
                    "role", "user".equals(msg.get("role")) ? "user" : "assistant",
                    "content", msg.get("content")
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModelName());
        body.put("max_tokens", 4096);
        body.put("system", systemContext);
        body.put("messages", messages);

        String responseBody = post(url, objectMapper.writeValueAsString(body), Map.of(
                "Content-Type", "application/json",
                "x-api-key", config.getApiKey(),
                "anthropic-version", "2023-06-01"
        ));

        JsonNode root = objectMapper.readTree(responseBody);

        if (root.has("error")) {
            String errorMsg = root.path("error").path("message").asText("Unknown Claude error");
            throw new RuntimeException("Claude API error: " + errorMsg);
        }

        return root.path("content").path(0).path("text").asText("");
    }

    // ── OpenAI ─────────────────────────────────────────────────────────────

    private String callOpenAI(AiAgentConfig config, String systemContext,
                              List<Map<String, String>> history) throws Exception {
        String url = "https://api.openai.com/v1/chat/completions";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemContext));
        for (Map<String, String> msg : history) {
            messages.add(Map.of("role", msg.get("role"), "content", msg.get("content")));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getModelName());
        body.put("messages", messages);
        body.put("max_tokens", 4096);
        body.put("temperature", 0.7);

        String responseBody = post(url, objectMapper.writeValueAsString(body), Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + config.getApiKey()
        ));

        JsonNode root = objectMapper.readTree(responseBody);

        if (root.has("error")) {
            String errorMsg = root.path("error").path("message").asText("Unknown OpenAI error");
            throw new RuntimeException("OpenAI API error: " + errorMsg);
        }

        return root.path("choices").path(0).path("message").path("content").asText("");
    }

    // ── System Context Builder ─────────────────────────────────────────────

    private String buildSystemContext() {
        try {
            Map<String, Object> health = healthService.getFullHealth();
            double deliveryRate = healthService.getDeliveryRate(60);
            long queued = healthService.getQueuedCount();
            long offline = healthService.getOfflineDeviceCount();

            return """
                    You are an AI system administrator assistant for a messaging platform called "Messaging Agent".
                    The platform is an SMPP-to-RCS gateway that receives SMS messages via SMPP protocol and delivers them as RCS messages through Android devices.
                    
                    YOUR ROLE:
                    - Monitor the platform's health and provide troubleshooting advice
                    - Diagnose issues based on the real-time metrics provided below
                    - Suggest fixes for common problems (device connectivity, SMSC connections, message delivery failures)
                    - Provide system maintenance recommendations
                    - Be concise and actionable in your responses
                    
                    CURRENT SYSTEM STATUS (REAL-TIME):
                    """ + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "platformHealth", health,
                    "deliveryRate1h", deliveryRate,
                    "queuedMessages", queued,
                    "offlineDevices", offline
            )) + """
                    
                    PLATFORM ARCHITECTURE:
                    - Backend: Spring Boot (Java 21) running in Docker
                    - Database: PostgreSQL 16
                    - Message Queue: Apache Kafka
                    - Cache: Redis (used for SMPP DLR correlation)
                    - SMSC Connections: CloudHopper SMPP 3.4 library
                    - Mobile Devices: Android phones running a custom APK that receives commands via WebSocket/STOMP
                    - Admin Panel: React (Vite) served via Nginx
                    
                    Answer questions about the system accurately. If metrics look concerning, proactively warn the user.
                    Use markdown formatting for readability. Keep responses focused and practical.
                    """;
        } catch (Exception e) {
            log.warn("Failed to build system context: {}", e.getMessage());
            return "You are an AI assistant for a messaging platform. System metrics are temporarily unavailable.";
        }
    }

    // ── HTTP Helper ────────────────────────────────────────────────────────

    private String post(String url, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        headers.forEach(builder::header);

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            log.error("LLM API returned HTTP {}: {}", response.statusCode(), response.body());
        }

        return response.body();
    }
}
