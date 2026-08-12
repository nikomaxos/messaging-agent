package com.messagingagent.device;

import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceLog;
import com.messagingagent.model.NotificationAlert;
import com.messagingagent.repository.DeviceLogRepository;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.NotificationAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-healing scheduler that reboots degraded devices.
 *
 * Runs every 5 minutes. For each device with selfHealingEnabled=true:
 *   Condition A: success rate < 50% over last 2h (excluding RCS_FAILED) → REBOOT
 *   Condition B: avg delivery latency > 30s over last 2h → REBOOT
 * Either condition (OR) triggers reboot.
 *
 * ESCALATION: After 3 consecutive reboots in 1 hour without improvement,
 * creates a CRITICAL alert via the notification system.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfHealingScheduler {

    private final DeviceRepository deviceRepository;
    private final DevicePerformanceService performanceService;
    private final DeviceLogRepository deviceLogRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationAlertRepository alertRepository;

    // Track consecutive reboots per device (resets on successful interval)
    private final Map<Long, RebootTracker> rebootTrackers = new ConcurrentHashMap<>();

    private record RebootTracker(int count, Instant firstRebootAt) {}

    @Scheduled(fixedRate = 300_000) // every 5 min
    public void checkDeviceHealth() {
        List<Device> devices = deviceRepository.findAll();
        for (Device device : devices) {
            if (!Boolean.TRUE.equals(device.getSelfHealingEnabled())) continue;
            if (device.getStatus() == Device.Status.OFFLINE) continue;

            try {
                DevicePerformanceService.DeviceScore score = performanceService.computeScore(device.getId());

                // Need at least 5 dispatches in the window to trigger
                if (score.totalDispatched() < 5) {
                    // Device is healthy — reset tracker
                    rebootTrackers.remove(device.getId());
                    continue;
                }

                String reason = null;
                if (score.successRate() < 0.50) {
                    reason = String.format("delivery rate %.0f%% < 50%% (last 2h, %d msgs)",
                            score.successRate() * 100, score.totalDispatched());
                } else if (score.avgLatencySeconds() > 30.0) {
                    reason = String.format("avg latency %.1fs > 30s (last 2h, %d msgs)",
                            score.avgLatencySeconds(), score.totalDispatched());
                }

                if (reason != null) {
                    // Track consecutive reboots
                    RebootTracker tracker = rebootTrackers.get(device.getId());
                    Instant now = Instant.now();

                    if (tracker == null || now.isAfter(tracker.firstRebootAt.plusSeconds(3600))) {
                        // First reboot or window expired — start fresh
                        tracker = new RebootTracker(1, now);
                    } else {
                        tracker = new RebootTracker(tracker.count + 1, tracker.firstRebootAt);
                    }
                    rebootTrackers.put(device.getId(), tracker);

                    if (tracker.count >= 3) {
                        // ESCALATION: 3+ reboots in 1 hour — create critical alert
                        String escalationMsg = String.format(
                                "Self-healing ESCALATION: Device %s (%s) rebooted %d times in 1h without improvement. Last reason: %s",
                                device.getName(), device.getHardwareId(), tracker.count, reason);
                        log.error(escalationMsg);

                        alertRepository.save(NotificationAlert.builder()
                                .type(com.messagingagent.model.NotificationConfig.AlertType.SELF_HEALING_ESCALATION)
                                .severity(NotificationAlert.Severity.CRITICAL)
                                .message(escalationMsg)
                                .build());

                        // Push to admin panel
                        messagingTemplate.convertAndSend("/topic/alerts", Map.of(
                                "severity", "CRITICAL",
                                "message", escalationMsg
                        ));

                        // Log it
                        deviceLogRepository.save(DeviceLog.builder()
                                .device(device)
                                .level("ERROR")
                                .event("SELF_HEALING_ESCALATION")
                                .detail(escalationMsg)
                                .build());

                        // Reset tracker to avoid flooding
                        rebootTrackers.remove(device.getId());
                        continue; // Don't reboot again
                    }

                    log.warn("Self-healing: rebooting device {} ({}): {}", device.getId(), device.getName(), reason);
                    messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "REBOOT");

                    deviceLogRepository.save(DeviceLog.builder()
                            .device(device)
                            .level("WARN")
                            .event("SELF_HEALING_REBOOT")
                            .detail(reason)
                            .build());

                    // Update device tracking fields
                    device.setSelfHealingRebootCount(
                            (device.getSelfHealingRebootCount() != null ? device.getSelfHealingRebootCount() : 0) + 1);
                    device.setLastSelfHealingAt(now);
                    deviceRepository.save(device);
                } else {
                    // Device is performing OK — reset tracker
                    rebootTrackers.remove(device.getId());
                }
            } catch (Exception e) {
                log.error("Self-healing check failed for device {}", device.getId(), e);
            }
        }
    }
}
