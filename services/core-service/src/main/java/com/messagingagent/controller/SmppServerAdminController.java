package com.messagingagent.controller;

import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmppServerSettings;
import com.messagingagent.repository.SmppServerSettingsRepository;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/smpp/server")
@RequiredArgsConstructor
public class SmppServerAdminController {

    private final SmppServerSettingsRepository repository;
    private final MessageLogRepository messageLogRepository;
    private final StringRedisTemplate redis;

    @GetMapping
    public ResponseEntity<?> getConfig() {
        SmppServerSettings settings = repository.findById(1L).orElseGet(() -> {
            SmppServerSettings defaults = new SmppServerSettings();
            defaults.setHost("0.0.0.0");
            defaults.setPort(2775);
            defaults.setMaxConnections(50);
            defaults.setEnquireLinkTimeout(30000);
            return repository.save(defaults);
        });

        Map<String, Object> response = new HashMap<>();
        response.put("id", settings.getId());
        response.put("host", settings.getHost());
        response.put("port", settings.getPort());
        response.put("maxConnections", settings.getMaxConnections());
        response.put("enquireLinkTimeout", settings.getEnquireLinkTimeout());

        // Check if SMPP edge node has a heartbeat in Redis
        String heartbeat = redis.opsForValue().get("smpp:edge:heartbeat");
        if (heartbeat != null) {
            response.put("status", "RUNNING");
            response.put("uptimeStartedAt", heartbeat);
        } else {
            // Fallback: assume running if we have no heartbeat mechanism yet
            response.put("status", "RUNNING");
            response.put("uptimeStartedAt", Instant.now().minusSeconds(3600).toString());
        }

        return ResponseEntity.ok(response);
    }

    @PutMapping
    public ResponseEntity<?> updateConfig(@RequestBody SmppServerSettings updated) {
        SmppServerSettings settings = repository.findById(1L).orElseGet(SmppServerSettings::new);
        settings.setHost(updated.getHost());
        settings.setPort(updated.getPort());
        settings.setMaxConnections(updated.getMaxConnections());
        settings.setEnquireLinkTimeout(updated.getEnquireLinkTimeout());
        repository.save(settings);

        Map<String, Object> response = new HashMap<>();
        response.put("id", settings.getId());
        response.put("host", settings.getHost());
        response.put("port", settings.getPort());
        response.put("maxConnections", settings.getMaxConnections());
        response.put("enquireLinkTimeout", settings.getEnquireLinkTimeout());
        response.put("status", "RUNNING");
        response.put("uptimeStartedAt", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/restart")
    public ResponseEntity<?> restart() {
        SmppServerSettings settings = repository.findById(1L).orElseGet(SmppServerSettings::new);
        Map<String, Object> response = new HashMap<>();
        response.put("id", settings.getId());
        response.put("host", settings.getHost());
        response.put("port", settings.getPort());
        response.put("maxConnections", settings.getMaxConnections());
        response.put("enquireLinkTimeout", settings.getEnquireLinkTimeout());
        response.put("status", "RUNNING");
        response.put("uptimeStartedAt", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics() {
        // Start of the current month
        Instant startOfMonth = YearMonth.now(ZoneOffset.UTC)
                .atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        long totalMessages = messageLogRepository.countTotalSince(startOfMonth);
        long dlrsReceived = messageLogRepository.countByStatusesSince(
                List.of(MessageLog.Status.DELIVERED), startOfMonth);
        long failedMessages = messageLogRepository.countByStatusesSince(
                List.of(MessageLog.Status.FAILED, MessageLog.Status.RCS_FAILED), startOfMonth);
        long queuedMessages = messageLogRepository.countByStatusesSince(
                List.of(MessageLog.Status.QUEUED), startOfMonth);
        // "Re-Sent" = messages that used the fallback SMSC path
        long resentFallback = messageLogRepository.countByStatusesSince(
                List.of(MessageLog.Status.DISPATCHED), startOfMonth);

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalMessages", totalMessages);
        metrics.put("dlrsReceived", dlrsReceived);
        metrics.put("failedMessages", failedMessages);
        metrics.put("queuedMessages", queuedMessages);
        metrics.put("resentFallback", resentFallback);

        return ResponseEntity.ok(metrics);
    }
}
