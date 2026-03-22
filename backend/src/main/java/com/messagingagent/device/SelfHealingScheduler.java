package com.messagingagent.device;

import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceLog;
import com.messagingagent.repository.DeviceLogRepository;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Self-healing scheduler that reboots degraded devices.
 *
 * Runs every 5 minutes. For each device with selfHealingEnabled=true:
 *   Condition A: success rate < 50% over last 2h (excluding RCS_FAILED) → REBOOT
 *   Condition B: avg delivery latency > 30s over last 2h → REBOOT
 * Either condition (OR) triggers reboot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SelfHealingScheduler {

    private final DeviceRepository deviceRepository;
    private final DevicePerformanceService performanceService;
    private final DeviceLogRepository deviceLogRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedRate = 300_000) // every 5 min
    public void checkDeviceHealth() {
        List<Device> devices = deviceRepository.findAll();
        for (Device device : devices) {
            if (!Boolean.TRUE.equals(device.getSelfHealingEnabled())) continue;
            if (device.getStatus() == Device.Status.OFFLINE) continue;

            try {
                DevicePerformanceService.DeviceScore score = performanceService.computeScore(device.getId());

                // Need at least 5 dispatches in the window to trigger
                if (score.totalDispatched() < 5) continue;

                String reason = null;
                if (score.successRate() < 0.50) {
                    reason = String.format("delivery rate %.0f%% < 50%% (last 2h, %d msgs)",
                            score.successRate() * 100, score.totalDispatched());
                } else if (score.avgLatencySeconds() > 30.0) {
                    reason = String.format("avg latency %.1fs > 30s (last 2h, %d msgs)",
                            score.avgLatencySeconds(), score.totalDispatched());
                }

                if (reason != null) {
                    log.warn("Self-healing: rebooting device {} ({}): {}", device.getId(), device.getName(), reason);
                    messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "REBOOT");

                    deviceLogRepository.save(DeviceLog.builder()
                            .device(device)
                            .level("WARN")
                            .event("SELF_HEALING_REBOOT")
                            .detail(reason)
                            .build());
                }
            } catch (Exception e) {
                log.error("Self-healing check failed for device {}", device.getId(), e);
            }
        }
    }
}
