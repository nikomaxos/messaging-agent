package com.messagingagent.notification;

import com.messagingagent.model.NotificationAlert;
import com.messagingagent.model.NotificationConfig;
import com.messagingagent.model.NotificationChannel;
import com.messagingagent.model.PushSubscription;
import com.messagingagent.model.AppUser;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.DeviceGroup;
import com.messagingagent.repository.NotificationAlertRepository;
import com.messagingagent.repository.NotificationConfigRepository;
import com.messagingagent.repository.PushSubscriptionRepository;
import com.messagingagent.repository.AppUserRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.service.PlatformHealthService;
import com.messagingagent.service.WebPushService;
import com.messagingagent.device.DeviceWebSocketService;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.UUID;

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
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final WebPushService webPushService;
    private final AppUserRepository appUserRepository;
    private final MessageLogRepository messageLogRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceWebSocketService deviceWebSocketService;
    private final SmscConnectionManager smscConnectionManager;

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
                
                var disconnectedSuppliers = suppliers.stream()
                        .filter(s -> Boolean.TRUE.equals(s.get("active")) 
                                  && !Boolean.TRUE.equals(s.get("connected"))
                                  && s.get("disconnectedSeconds") instanceof Number
                                  && ((Number) s.get("disconnectedSeconds")).longValue() > 30)
                        .map(s -> String.valueOf(s.get("name")))
                        .toList();
                        
                long disconnected = disconnectedSuppliers.size();
                if (disconnected > 0) {
                    String names = String.join(", ", disconnectedSuppliers);
                    yield new AlertResult(true, disconnected,
                            String.format("%d active SMSC supplier(s) disconnected: %s", disconnected, names),
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
            case POSSIBLE_AIT_TRAFFIC -> {
                int trafficThreshold = (int) config.getThreshold();
                long suspiciousNumbers = healthService.evaluateSuspiciousAitNumbers(60, trafficThreshold, config.isAutoBlock());
                if (suspiciousNumbers > 0) {
                    yield new AlertResult(true, suspiciousNumbers,
                            String.format("%d numbers flagged for AIT (received >= %d messages in last hour)", suspiciousNumbers, trafficThreshold),
                            NotificationAlert.Severity.CRITICAL);
                }
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

        // Dispatch to configured channels
        if (config.getChannels() != null && !config.getChannels().isEmpty()) {
            dispatchToChannels(config, result);
        }
    }

    private void dispatchToChannels(NotificationConfig config, AlertResult result) {
        String alertTitle = "System Alert: " + config.getType();
        String alertBody = result.message;

        if (config.getChannels().contains(NotificationChannel.BROWSER_PUSH)) {
            List<PushSubscription> subs = pushSubscriptionRepository.findAll();
            if (!subs.isEmpty()) {
                webPushService.sendNotifications(subs, alertTitle, alertBody, "/dashboard");
            }
        }

        boolean sendRcs = config.getChannels().contains(NotificationChannel.RCS_VIRTUAL_SMSC) && config.getAlertDeviceGroupId() != null;
        boolean sendSmpp = config.getChannels().contains(NotificationChannel.SMPP_SUPPLIER) && config.getAlertSmppSupplierId() != null;

        if (sendRcs || sendSmpp) {
            List<AppUser> admins = appUserRepository.findAll().stream()
                    .filter(u -> u.getAlertPhoneNumber() != null && !u.getAlertPhoneNumber().trim().isEmpty())
                    .toList();

            for (AppUser admin : admins) {
                if (sendRcs) {
                    deviceGroupRepository.findById(config.getAlertDeviceGroupId()).ifPresent(group -> {
                        String msgId = "rcs_alert_" + UUID.randomUUID().toString().substring(0, 8);
                        MessageLog msgLog = MessageLog.builder()
                                .smppMessageId(msgId)
                                .customerMessageId(msgId)
                                .sourceAddress("SYSTEM")
                                .destinationAddress(admin.getAlertPhoneNumber())
                                .messageText(alertBody)
                                .status(MessageLog.Status.QUEUED)
                                .deviceGroup(group)
                                .createdAt(Instant.now())
                                .build();
                        messageLogRepository.save(msgLog);
                        deviceWebSocketService.drainQueueForGroup(group);
                    });
                }
                if (sendSmpp) {
                    smscConnectionManager.submitMessage(
                            config.getAlertSmppSupplierId(),
                            "SYSTEM",
                            admin.getAlertPhoneNumber(),
                            alertBody
                    );
                }
            }
        }
    }

    @Scheduled(cron = "0 0 0 * * ?") // Runs every day at midnight
    @org.springframework.transaction.annotation.Transactional
    public void purgeOldAlerts() {
        try {
            Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS);
            int purged = alertRepository.deleteOlderThan(cutoff);
            if (purged > 0) {
                log.info("Purged {} archived alerts older than 60 days", purged);
            }
        } catch (Exception e) {
            log.error("Failed to purge old notification alerts", e);
        }
    }
}
