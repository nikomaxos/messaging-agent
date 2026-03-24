package com.messagingagent.device;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.kafka.SmsInboundEvent;
import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.routing.RoundRobinLoadBalancer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.messagingagent.model.DeviceGroup;
import com.messagingagent.model.DeviceLog;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.DeviceLogRepository;
import com.messagingagent.repository.MessageLogRepository;

/**
 * Service responsible for all WebSocket communication with Android devices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;
    private final KafkaTemplate<String, SmsDeliveryResultEvent> deliveryResultKafka;
    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;
    private final DeviceLogRepository deviceLogRepository;
    private final RoundRobinLoadBalancer loadBalancer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * In-memory pending command queue per device ID.
     * REST controllers enqueue commands here; heartbeat/ping handlers drain them.
     * This works around Spring's simple STOMP broker not delivering convertAndSend()
     * from servlet threads to WebSocket subscriptions.
     */
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> pendingCommandQueue = new ConcurrentHashMap<>();

    /** Queue a command for delivery on the next heartbeat/ping. */
    public void queueCommand(Long deviceId, String command) {
        pendingCommandQueue.computeIfAbsent(deviceId, k -> new ConcurrentLinkedQueue<>()).offer(command);
        log.info("Queued command '{}' for device {} (will deliver on next heartbeat)", command, deviceId);
    }

    /** Drain all pending commands for a device — called from WebSocket thread context. */
    private void drainPendingCommands(Long deviceId) {
        ConcurrentLinkedQueue<String> queue = pendingCommandQueue.get(deviceId);
        if (queue == null) return;
        String cmd;
        while ((cmd = queue.poll()) != null) {
            log.info("Delivering queued command '{}' to device {} via heartbeat context", cmd, deviceId);
            messagingTemplate.convertAndSend("/queue/commands." + deviceId, cmd);
        }
    }

    /** Send an SMS dispatch command to a specific device via its STOMP user-queue. */
    public void sendSmsToDevice(Device device, SmsInboundEvent event) {
        String destination = "/queue/sms." + device.getId();
        messagingTemplate.convertAndSend(destination, event);
        log.debug("Sent SMS dispatch to device {} via WebSocket", device.getId());
    }

    /** Send a generic System Command to a specific device's command queue. */
    public void sendSysCommand(Device device, String command) {
        String destination = "/queue/commands." + device.getId();
        messagingTemplate.convertAndSend(destination, command);
        log.debug("Sent System Command '{}' to device {} via WebSocket", command, device.getId());
    }

    /** Lightweight ping — just confirms alive + triggers queue drain. No sensor data. */
    public void handlePing(String deviceToken) {
        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            device.setLastHeartbeat(Instant.now());
            // Only set ONLINE if device was OFFLINE (reconnected). NEVER override BUSY!
            if (device.getStatus() == Device.Status.OFFLINE) {
                device.setStatus(Device.Status.ONLINE);
                device.setConnectedAt(Instant.now()); // Reset uptime on reconnect
                // Persist ONLINE event as a device log
                deviceLogRepository.save(DeviceLog.builder()
                        .device(device).level("INFO").event("Device ONLINE")
                        .detail("Reconnected via ping").build());
            }
            deviceRepository.save(device);
            // Drain any pending commands queued by REST controllers
            drainPendingCommands(device.getId());
            // Only drain queue if device is actually available (ONLINE, not BUSY)
            if (device.getGroup() != null && device.getStatus() == Device.Status.ONLINE) {
                drainQueueForGroup(device.getGroup());
            }
        });
    }

    /** Called when a device reports a full heartbeat via WebSocket. */
    public void handleHeartbeat(String deviceToken, DeviceHeartbeat heartbeat) {
        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            device.setBatteryPercent(heartbeat.getBatteryPercent());
            device.setIsCharging(heartbeat.getIsCharging());
            device.setWifiSignalDbm(heartbeat.getWifiSignalDbm());
            device.setGsmSignalDbm(heartbeat.getGsmSignalDbm());
            device.setGsmSignalAsu(heartbeat.getGsmSignalAsu());
            device.setNetworkOperator(heartbeat.getNetworkOperator());
            device.setRcsCapable(heartbeat.getRcsCapable());
            device.setActiveNetworkType(heartbeat.getActiveNetworkType());
            device.setApkVersion(heartbeat.getApkVersion());
            // Sync phoneNumber from heartbeat only if device doesn't already have one
            // (admin-panel edits take priority over auto-detection)
            if (heartbeat.getPhoneNumber() != null && !heartbeat.getPhoneNumber().isBlank()
                    && (device.getPhoneNumber() == null || device.getPhoneNumber().isBlank())) {
                device.setPhoneNumber(heartbeat.getPhoneNumber().trim());
            }
            device.setApkUpdateStatus(null);
            if (heartbeat.getAdbWifiAddress() != null && !heartbeat.getAdbWifiAddress().isBlank()) {
                device.setAdbWifiAddress(heartbeat.getAdbWifiAddress().trim());
            }
            // Only set ONLINE if device was OFFLINE (reconnected). NEVER override BUSY!
            if (device.getStatus() != Device.Status.BUSY) {
                if (device.getStatus() == Device.Status.OFFLINE) {
                    device.setConnectedAt(Instant.now()); // Reset uptime on reconnect
                    // Persist ONLINE event as a device log
                    deviceLogRepository.save(DeviceLog.builder()
                            .device(device).level("INFO").event("Device ONLINE")
                            .detail("battery=" + heartbeat.getBatteryPercent() + "% network=" + heartbeat.getActiveNetworkType()).build());
                }
                device.setStatus(Device.Status.ONLINE);
            }
            device.setLastHeartbeat(Instant.now());
            deviceRepository.save(device);

            // Push critical config back to device on every heartbeat to keep DataStore in sync
            // (covers cases where the device was offline when admin panel sent the command)
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SET_AUTO_REBOOT=" + device.getAutoRebootEnabled());
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SET_SILENT=" + device.getSilentMode());
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SET_CALL_BLOCK=" + device.getCallBlockEnabled());
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SET_AUTO_PURGE=" + (device.getAutoPurge() != null ? device.getAutoPurge() : "OFF"));
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SET_SELF_HEALING=" + device.getSelfHealingEnabled());
            // Drain any pending commands queued by REST controllers
            drainPendingCommands(device.getId());
            log.debug("Heartbeat from device {}: battery={}% charging={} wifi={}dBm gsm={}dBm network={}",
                    device.getName(), heartbeat.getBatteryPercent(), heartbeat.getIsCharging(),
                    heartbeat.getWifiSignalDbm(), heartbeat.getGsmSignalDbm(),
                    heartbeat.getActiveNetworkType());
            
            // If the device just came online (or was busy and fell out of sync), trigger a queue drain check
            if (device.getGroup() != null && device.getStatus() == Device.Status.ONLINE) {
                drainQueueForGroup(device.getGroup());
            }

            var statusMap = new java.util.HashMap<String, Object>();
            statusMap.put("id", device.getId());
            statusMap.put("name", device.getName());
            statusMap.put("status", "ONLINE");
            statusMap.put("batteryPercent", heartbeat.getBatteryPercent() != null ? heartbeat.getBatteryPercent() : "");
            statusMap.put("isCharging", heartbeat.getIsCharging() != null ? heartbeat.getIsCharging() : false);
            statusMap.put("wifiSignalDbm", heartbeat.getWifiSignalDbm() != null ? heartbeat.getWifiSignalDbm() : "");
            statusMap.put("gsmSignalDbm", heartbeat.getGsmSignalDbm() != null ? heartbeat.getGsmSignalDbm() : "");
            statusMap.put("activeNetworkType", heartbeat.getActiveNetworkType() != null ? heartbeat.getActiveNetworkType() : "");
            statusMap.put("apkVersion", heartbeat.getApkVersion() != null ? heartbeat.getApkVersion() : "");
            statusMap.put("apkUpdateStatus", "");
            statusMap.put("lastHeartbeat", device.getLastHeartbeat().toString());
            if (device.getConnectedAt() != null) {
                statusMap.put("connectedAt", device.getConnectedAt().toString());
            }
            messagingTemplate.convertAndSend("/topic/devices", statusMap);
        });
    }

    /** Called when a device reports delivery result. Forwards to Kafka. */
    public void handleDeliveryResult(SmsDeliveryResultEvent result, String deviceToken) {
        deliveryResultKafka.send("sms.delivery.result", result.getCorrelationId(), result);

        // Only unlock the device on FINAL results (DELIVERED, FAILED, NO_RCS, ERROR).
        // SENT = message entered bugle_db but DLR is still pending — keep device BUSY
        // to prevent dispatching the next message before this one's DLR is resolved.
        boolean isFinalResult = result.getResult() != SmsDeliveryResultEvent.Result.SENT;

        if (deviceToken != null && isFinalResult) {
            deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
                if (device.getStatus() == Device.Status.BUSY) {
                    device.setStatus(Device.Status.ONLINE);
                    deviceRepository.save(device);
                    log.info("Device {} unlocked BUSY→ONLINE after final DLR: {}", device.getName(), result.getResult());
                    
                    messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", device.getId(), "status", "ONLINE"));
                    
                    if (device.getGroup() != null) {
                        // If device has a send interval, schedule delayed drain
                        double interval = device.getSendIntervalSeconds() != null ? device.getSendIntervalSeconds() : 0.0;
                        if (interval > 0) {
                            long delayMs = (long) (interval * 1000);
                            log.debug("Device {} has {}s send interval, scheduling drain in {}ms", device.getName(), interval, delayMs);
                            DeviceGroup grp = device.getGroup();
                            scheduler.schedule(() -> drainQueueForGroup(grp), delayMs, TimeUnit.MILLISECONDS);
                        } else {
                            drainQueueForGroup(device.getGroup());
                        }
                    }
                }
            });
        } else if (deviceToken != null && !isFinalResult) {
            log.debug("SENT signal for device — keeping BUSY while awaiting final DLR");
        }
    }

    /** 
     * Drain the message queue for the given DeviceGroup.
     * Dispatches queued messages to ALL available ONLINE devices for maximum TPS.
     * Uses the load balancer for fair device selection.
     */
    public synchronized void drainQueueForGroup(DeviceGroup group) {
        List<Device> onlineDevices = deviceRepository.findByGroupAndStatus(group, Device.Status.ONLINE);
        if (onlineDevices.isEmpty()) {
            return;
        }

        // Track the earliest cooldown expiry for scheduling a delayed re-drain
        long earliestCooldownMs = Long.MAX_VALUE;

        // Drain as many queued messages as there are available devices
        while (true) {
            // Re-fetch available (non-rate-limited) devices
            List<Device> available = onlineDevices.stream().filter(d -> {
                double interval = d.getSendIntervalSeconds() != null ? d.getSendIntervalSeconds() : 0.0;
                if (interval > 0 && d.getLastDispatchedAt() != null) {
                    long elapsedMs = Duration.between(d.getLastDispatchedAt(), Instant.now()).toMillis();
                    long intervalMs = (long) (interval * 1000);
                    if (elapsedMs < intervalMs) {
                        return false; // still in cooldown
                    }
                }
                return d.getStatus() == Device.Status.ONLINE;
            }).collect(java.util.stream.Collectors.toList());

            if (available.isEmpty()) {
                break;
            }

            Optional<MessageLog> queuedOpt = messageLogRepository.findFirstByStatusAndDeviceGroupIdOrderByCreatedAtAsc(
                    MessageLog.Status.QUEUED, group.getId()
            );
            if (queuedOpt.isEmpty()) {
                break; // no more queued messages
            }

            MessageLog queued = queuedOpt.get();
            Optional<Device> selectedOpt = loadBalancer.selectDevice(group, available);
            if (selectedOpt.isEmpty()) {
                break;
            }
            Device target = selectedOpt.get();
            loadBalancer.recordDispatch(target.getId());

            log.info("Draining QUEUED message id={} to device id={} (fair LB)", queued.getSmppMessageId(), target.getId());

            // Lock device
            target.setStatus(Device.Status.BUSY);
            deviceRepository.save(target);
            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", target.getId(), "status", "BUSY"));

            // Dispatch message
            queued.setStatus(MessageLog.Status.DISPATCHED);
            queued.setDevice(target);
            queued.setDispatchedAt(Instant.now());
            messageLogRepository.save(queued);

            // Track dispatch time for rate limiting
            target.setLastDispatchedAt(Instant.now());
            deviceRepository.save(target);

            SmsInboundEvent event = new SmsInboundEvent();
            event.setCorrelationId(queued.getSmppMessageId());
            event.setSourceAddress(queued.getSourceAddress());
            event.setDestinationAddress(queued.getDestinationAddress());
            event.setMessageText(queued.getMessageText());
            sendSmsToDevice(target, event);

            // Remove from available pool (now BUSY)
            onlineDevices.remove(target);
        }

        // Schedule a delayed re-drain for any devices in rate-limit cooldown
        for (Device d : onlineDevices) {
            double interval = d.getSendIntervalSeconds() != null ? d.getSendIntervalSeconds() : 0.0;
            if (interval > 0 && d.getLastDispatchedAt() != null) {
                long elapsedMs = Duration.between(d.getLastDispatchedAt(), Instant.now()).toMillis();
                long intervalMs = (long) (interval * 1000);
                long remaining = intervalMs - elapsedMs;
                if (remaining > 0 && remaining < earliestCooldownMs) {
                    earliestCooldownMs = remaining;
                }
            }
        }
        if (earliestCooldownMs < Long.MAX_VALUE) {
            log.debug("Scheduling delayed drain in {}ms for group {}", earliestCooldownMs, group.getName());
            DeviceGroup grp = group;
            scheduler.schedule(() -> drainQueueForGroup(grp), earliestCooldownMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Unlock a device from BUSY to ONLINE and trigger queue drain for the given group.
     * Called by RcsExpirationService when messages expire without a DLR from the APK.
     */
    public void unlockDeviceAndDrainQueue(Device device, DeviceGroup group) {
        if (device != null && device.getStatus() == Device.Status.BUSY) {
            device.setStatus(Device.Status.ONLINE);
            deviceRepository.save(device);
            log.info("Device {} unlocked BUSY→ONLINE after expiration", device.getName());
            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", device.getId(), "status", "ONLINE"));
        }
        if (group != null) {
            drainQueueForGroup(group);
        }
    }

    /** Called when a device reports APK update progress. */
    public void handleApkStatus(String deviceToken, String status) {
        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            device.setApkUpdateStatus(status);
            deviceRepository.save(device);
            log.info("APK update status from device {}: {}", device.getName(), status);
            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of(
                    "id", device.getId(),
                    "apkUpdateStatus", status
            ));
        });
    }

    /** Called when a device sends batched logs (connection events, errors, etc). */
    public void handleDeviceLogs(String deviceToken, java.util.List<java.util.Map<String, String>> logs) {
        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            for (var entry : logs) {
                String level = entry.getOrDefault("level", "INFO");
                String event = entry.getOrDefault("event", "");
                String detail = entry.get("detail");
                if (event.isEmpty()) continue;
                deviceLogRepository.save(DeviceLog.builder()
                        .device(device)
                        .level(level)
                        .event(event)
                        .detail(detail)
                        .build());
            }
            log.debug("Persisted {} device logs from {}", logs.size(), device.getName());
        });
    }
}
