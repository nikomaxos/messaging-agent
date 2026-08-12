package com.messagingagent.device;

import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Heartbeat watchdog that solves two ghost-device problems:
 *
 * 1. SERVER RESTART: On startup, all devices are marked OFFLINE because no
 *    WebSocket sessions exist yet. Without this, devices that were ONLINE
 *    before a restart remain stuck as ONLINE in the DB forever.
 *
 * 2. SILENT DISCONNECTS: Every 60s, any device whose lastHeartbeat is older
 *    than 2 minutes is marked OFFLINE. This catches connections that dropped
 *    without a clean TCP FIN (e.g. network outage, phone battery died).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceHeartbeatWatchdog {

    private final DeviceRepository deviceRepository;
    private final SimpMessagingTemplate broker;

    /** How long without a heartbeat before we consider a device dead. */
    private static final long STALE_THRESHOLD_MINUTES = 2;

    /**
     * On server startup: reset ALL devices to OFFLINE.
     * No WebSocket sessions survive a restart, so any ONLINE status is stale.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resetAllDevicesOnStartup() {
        List<Device> onlineDevices = deviceRepository.findByStatus(Device.Status.ONLINE);
        List<Device> busyDevices = deviceRepository.findByStatus(Device.Status.BUSY);

        int count = 0;
        for (Device device : onlineDevices) {
            device.setStatus(Device.Status.OFFLINE);
            device.setSessionId(null);
            device.setInFlightDispatches(0);
            deviceRepository.save(device);
            broadcastOffline(device);
            count++;
        }
        for (Device device : busyDevices) {
            device.setStatus(Device.Status.OFFLINE);
            device.setSessionId(null);
            device.setInFlightDispatches(0);
            deviceRepository.save(device);
            broadcastOffline(device);
            count++;
        }

        if (count > 0) {
            log.info("Startup watchdog: reset {} ghost device(s) to OFFLINE", count);
        } else {
            log.info("Startup watchdog: no ghost devices found");
        }
    }

    /**
     * Periodic check: mark devices as OFFLINE if their lastHeartbeat
     * is older than the stale threshold.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 120_000) // every 60s, first run after 2 min
    public void reapStaleDevices() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD_MINUTES, ChronoUnit.MINUTES);

        List<Device> onlineDevices = deviceRepository.findByStatus(Device.Status.ONLINE);
        List<Device> busyDevices = deviceRepository.findByStatus(Device.Status.BUSY);

        int reaped = 0;
        for (Device device : onlineDevices) {
            if (isStale(device, cutoff)) {
                markOffline(device, "heartbeat stale (>2 min)");
                reaped++;
            }
        }
        for (Device device : busyDevices) {
            if (isStale(device, cutoff)) {
                markOffline(device, "heartbeat stale while BUSY (>2 min)");
                reaped++;
            }
        }

        if (reaped > 0) {
            log.info("Heartbeat watchdog: reaped {} stale device(s)", reaped);
        }
    }

    private boolean isStale(Device device, Instant cutoff) {
        // No heartbeat ever recorded → definitely stale
        if (device.getLastHeartbeat() == null) return true;
        return device.getLastHeartbeat().isBefore(cutoff);
    }

    private void markOffline(Device device, String reason) {
        log.warn("Watchdog marking device '{}' (id={}) OFFLINE — {}", device.getName(), device.getId(), reason);
        device.setStatus(Device.Status.OFFLINE);
        device.setSessionId(null);
        device.setInFlightDispatches(0);
        deviceRepository.save(device);
        broadcastOffline(device);
    }

    private void broadcastOffline(Device device) {
        broker.convertAndSend("/topic/devices",
                Map.of("id", device.getId(), "name", device.getName(),
                        "status", "OFFLINE", "lastHeartbeat", ""));
    }
}
