package com.messagingagent.notification;

import com.messagingagent.model.NotificationAlert;
import com.messagingagent.model.NotificationConfig;
import com.messagingagent.repository.NotificationAlertRepository;
import com.messagingagent.repository.NotificationConfigRepository;
import com.messagingagent.service.PlatformHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Evaluates all enabled notification configs every 60 seconds.
 * When a threshold is breached and cooldown has elapsed, fires an alert
 * to the admin panel via STOMP and persists it for history.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertScheduler {

    private final NotificationConfigRepository configRepository;
    private final NotificationAlertRepository alertRepository;
    private final PlatformHealthService healthService;
    private final SimpMessagingTemplate stomp;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void evaluateAlerts() {
        for (NotificationConfig config : configRepository.findByEnabledTrue()) {
            // Skip if still in cooldown
            if (config.getLastTriggeredAt() != null &&
                config.getLastTriggeredAt().plus(config.getCooldownMinutes(), ChronoUnit.MINUTES).isAfter(Instant.now())) {
                continue;
            }

            try {
                AlertResult result = evaluate(config);
                if (result != null && result.triggered) {
                    fireAlert(config, result);
                }
            } catch (Exception e) {
                log.warn("Error evaluating alert config id={}: {}", config.getId(), e.getMessage());
            }
        }
    }

    private record AlertResult(boolean triggered, double metricValue, String message, NotificationAlert.Severity severity) {}

    private AlertResult evaluate(NotificationConfig config) {
        return switch (config.getType()) {
            case LOW_DELIVERY_RATE -> {
                double rate = healthService.getDeliveryRate(60); // last 60 min
                if (rate < config.getThreshold()) {
                    NotificationAlert.Severity sev = rate < config.getThreshold() / 2 ?
                            NotificationAlert.Severity.CRITICAL : NotificationAlert.Severity.WARNING;
                    yield new AlertResult(true, rate,
                            String.format("Delivery rate %.1f%% is below threshold %.0f%%", rate, config.getThreshold()), sev);
                }
                yield null;
            }
            case QUEUE_BUILDUP -> {
                long queued = healthService.getQueuedCount();
                if (queued > config.getThreshold()) {
                    NotificationAlert.Severity sev = queued > config.getThreshold() * 2 ?
                            NotificationAlert.Severity.CRITICAL : NotificationAlert.Severity.WARNING;
                    yield new AlertResult(true, queued,
                            String.format("Queue has %d messages (threshold: %.0f)", queued, config.getThreshold()), sev);
                }
                yield null;
            }
            case DEVICE_OFFLINE -> {
                long offline = healthService.getOfflineDeviceCount();
                if (offline > config.getThreshold()) {
                    yield new AlertResult(true, offline,
                            String.format("%d devices offline (threshold: %.0f)", offline, config.getThreshold()),
                            NotificationAlert.Severity.WARNING);
                }
                yield null;
            }
            case SMSC_DISCONNECT -> {
                var health = healthService.getFullHealth();
                @SuppressWarnings("unchecked")
                var suppliers = (java.util.List<Map<String, Object>>) health.get("smscSuppliers");
                long disconnected = suppliers.stream()
                        .filter(s -> Boolean.TRUE.equals(s.get("active")) && !Boolean.TRUE.equals(s.get("connected")))
                        .count();
                if (disconnected > 0) {
                    yield new AlertResult(true, disconnected,
                            String.format("%d active SMSC supplier(s) disconnected", disconnected),
                            NotificationAlert.Severity.CRITICAL);
                }
                yield null;
            }
            case HIGH_LATENCY -> {
                // Could add avg latency metric — for now yield null
                yield null;
            }
            case SELF_HEALING_ESCALATION -> {
                // Escalation alerts are created directly by SelfHealingScheduler
                yield null;
            }
        };
    }

    private void fireAlert(NotificationConfig config, AlertResult result) {
        log.warn("🚨 ALERT [{}]: {}", config.getType(), result.message);

        // Update cooldown timestamp
        config.setLastTriggeredAt(Instant.now());
        configRepository.save(config);

        // Persist alert
        NotificationAlert alert = NotificationAlert.builder()
                .type(config.getType())
                .severity(result.severity)
                .message(result.message)
                .metricValue(result.metricValue)
                .thresholdValue(config.getThreshold())
                .build();
        alertRepository.save(alert);

        // Push to admin panel via STOMP
        stomp.convertAndSend("/topic/alerts", Map.of(
                "type", config.getType().name(),
                "severity", result.severity.name(),
                "message", result.message,
                "metricValue", result.metricValue,
                "thresholdValue", config.getThreshold(),
                "timestamp", Instant.now().toString()
        ));
    }
}
