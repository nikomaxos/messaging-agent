package com.messagingagent.admin;

import com.messagingagent.model.SmppServerSettings;
import com.messagingagent.repository.SmppServerSettingsRepository;
import com.messagingagent.smpp.SmppServerService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/admin/smpp/server")
@RequiredArgsConstructor
public class SmppServerAdminController {

    private final SmppServerSettingsRepository settingsRepository;
    private final SmppServerService smppServerService;
    private final com.messagingagent.repository.MessageLogRepository messageLogRepository;

    @GetMapping
    public ServerConfigInfo getServerConfig() {
        SmppServerSettings settings = settingsRepository.findById(1L).orElse(new SmppServerSettings());
        Instant uptimeStart = smppServerService.getUptimeStartedAt();
        
        return ServerConfigInfo.builder()
                .host(settings.getHost() != null ? settings.getHost() : "0.0.0.0")
                .port(settings.getPort() != 0 ? settings.getPort() : 2775)
                .maxConnections(settings.getMaxConnections() != 0 ? settings.getMaxConnections() : 50)
                .enquireLinkTimeout(settings.getEnquireLinkTimeout() != 0 ? settings.getEnquireLinkTimeout() : 30000)
                .status(uptimeStart != null ? "RUNNING" : "STOPPED")
                .uptimeStartedAt(uptimeStart != null ? uptimeStart.toString() : null)
                .build();
    }

    @PutMapping
    public ResponseEntity<ServerConfigInfo> updateServerConfig(@RequestBody SmppServerSettings newSettings) {
        SmppServerSettings settings = settingsRepository.findById(1L).orElse(new SmppServerSettings());
        settings.setId(1L);
        settings.setHost(newSettings.getHost());
        settings.setPort(newSettings.getPort());
        settings.setMaxConnections(newSettings.getMaxConnections());
        settings.setEnquireLinkTimeout(newSettings.getEnquireLinkTimeout());
        settingsRepository.save(settings);
        return ResponseEntity.ok(getServerConfig());
    }

    @PostMapping("/restart")
    public ResponseEntity<ServerConfigInfo> restartServer() {
        smppServerService.restart();
        return ResponseEntity.ok(getServerConfig());
    }

    @Data
    @Builder
    public static class ServerConfigInfo {
        private String host;
        private int port;
        private int maxConnections;
        private int enquireLinkTimeout;
        private String status;
        private String uptimeStartedAt;
    }

    @GetMapping("/metrics")
    public ResponseEntity<ServerMetrics> getServerMetrics() {
        java.time.Instant startOfMonth = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toInstant();

        long totalMessages = messageLogRepository.countTotalSince(startOfMonth);
        
        long dlrsReceived = messageLogRepository.countByStatusesSince(
                java.util.List.of(com.messagingagent.model.MessageLog.Status.DELIVERED), startOfMonth);
                
        long failedMessages = messageLogRepository.countByStatusesSince(
                java.util.List.of(com.messagingagent.model.MessageLog.Status.FAILED), startOfMonth);

        long queuedMessages = messageLogRepository.countByStatusesSince(
                java.util.List.of(
                        com.messagingagent.model.MessageLog.Status.RECEIVED,
                        com.messagingagent.model.MessageLog.Status.DISPATCHED
                ), startOfMonth);
                
        long resentFallback = messageLogRepository.countByStatusesSince(
                java.util.List.of(com.messagingagent.model.MessageLog.Status.RCS_FAILED), startOfMonth);

        return ResponseEntity.ok(ServerMetrics.builder()
                .totalMessages(totalMessages)
                .dlrsReceived(dlrsReceived)
                .failedMessages(failedMessages)
                .queuedMessages(queuedMessages)
                .resentFallback(resentFallback)
                .build());
    }

    @Data
    @Builder
    public static class ServerMetrics {
        private long totalMessages;
        private long dlrsReceived;
        private long failedMessages;
        private long queuedMessages;
        private long resentFallback;
    }
}
