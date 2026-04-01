package com.messagingagent.device;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.kafka.SmsInboundEvent;
import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.routing.RoundRobinLoadBalancer;
import com.messagingagent.routing.MatrixRouteService;
import com.messagingagent.routing.MatrixQueueService;
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
    private final MatrixQueueService matrixQueueService;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * In-memory pending command queue per device ID.
     * REST controllers enqueue commands here; heartbeat/ping handlers drain them.
     * This works around Spring's simple STOMP broker not delivering convertAndSend()
     * from servlet threads to WebSocket subscriptions.
     */
    private final ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> pendingCommandQueue = new ConcurrentHashMap<>();

    /** Rate-limiter for auto-OTA — last push time per device. */
    private final ConcurrentHashMap<Long, Instant> lastOtaPushTime = new ConcurrentHashMap<>();

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
            if (heartbeat.getLatitude() != null) {
                device.setLatitude(heartbeat.getLatitude());
            }
            if (heartbeat.getLongitude() != null) {
                device.setLongitude(heartbeat.getLongitude());
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

            // Auto-OTA: if device reports an outdated APK version, push update (rate-limited per device)
            try {
                String latestVersion = getLatestOtaVersion();
                if (latestVersion != null && heartbeat.getApkVersion() != null
                        && !latestVersion.equals(heartbeat.getApkVersion())) {
                    Instant lastPush = lastOtaPushTime.get(device.getId());
                    if (lastPush == null || Duration.between(lastPush, Instant.now()).toMinutes() >= 10) {
                        log.info("AUTO-OTA: device {} reports v{} but server has v{} — sending UPDATE_APK",
                                device.getName(), heartbeat.getApkVersion(), latestVersion);
                        messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "UPDATE_APK");
                        lastOtaPushTime.put(device.getId(), Instant.now());
                    }
                }
            } catch (Exception e) {
                log.debug("Auto-OTA version check failed: {}", e.getMessage());
            }

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
            statusMap.put("phoneNumber", device.getPhoneNumber()); // use device entity to retain even if hb null
            statusMap.put("adbWifiAddress", device.getAdbWifiAddress());
            statusMap.put("latitude", device.getLatitude());
            statusMap.put("longitude", device.getLongitude());
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
            // Check if this message was already dynamically expired by RcsExpirationService
            Optional<MessageLog> msgOpt = messageLogRepository.findBySmppMessageId(result.getCorrelationId());
            if (msgOpt.isPresent() && msgOpt.get().getRcsExpiresAt() == null && msgOpt.get().getErrorDetail() != null && msgOpt.get().getErrorDetail().contains("timed out")) {
                log.warn("Ignoring inFlight decrement for stale final DLR '{}' from {} (already decremented by RcsExpirationService)", 
                         result.getResult(), result.getCorrelationId());
            } else {
                deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
                    deviceRepository.decrementInFlight(device.getId());
                    int newInFlight = Math.max(0, (device.getInFlightDispatches() != null ? device.getInFlightDispatches() : 0) - 1);
                    device.setInFlightDispatches(newInFlight);
                    
                    log.info("Device {} finished DLR processing for {}. InFlight is now {}", device.getName(), result.getCorrelationId(), newInFlight);
                
                    messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", device.getId(), "inFlightDispatches", newInFlight));
                
                    if (device.getGroup() != null) {
                        // If device has a send interval, schedule delayed drain
                        double interval = device.getSendIntervalSeconds() != null ? device.getSendIntervalSeconds() : 0.0;
                        if (interval > 0) {
                            long delayMs = (long) (interval * 1000);
                            DeviceGroup grp = device.getGroup();
                            scheduler.schedule(() -> drainQueueForGroup(grp), delayMs, TimeUnit.MILLISECONDS);
                        } else {
                            drainQueueForGroup(device.getGroup());
                        }
                    }
                });
            }
        } else if (deviceToken != null && !isFinalResult) {
            log.debug("SENT signal for device — keeping BUSY while awaiting final DLR");
        }
    }

    /** Called when the device sends a real-time bulk DLR sync from native sqlite */
    public void handleDeliveryBulk(String base64Data, String deviceToken) {
        // Disabled: Backend-side bulk tracking has been replaced by the superior 
        // device-side TRACK_DLR_ONLY architecture for Matrix messages. 
        // Keeping endpoint alive temporarily for backward compatibility with old APKs.
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
            // Re-fetch available (non-rate-limited) devices that have not hit the InFlight Token Bucket capacity
            List<Device> available = onlineDevices.stream().filter(d -> {
                int inFlight = d.getInFlightDispatches() != null ? d.getInFlightDispatches() : 0;
                if (inFlight >= Device.MAX_CONCURRENT_DISPATCHES) {
                    return false; // Token bucket is full
                }
                
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

            // Lock device natively (increment inFlight Token Bucket without altering status)
            deviceRepository.incrementInFlight(target.getId());
            target.setInFlightDispatches((target.getInFlightDispatches() != null ? target.getInFlightDispatches() : 0) + 1);

            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", target.getId(), "inFlightDispatches", target.getInFlightDispatches()));

            // Dispatch message
            queued.setStatus(MessageLog.Status.DISPATCHED);
            queued.setDevice(target);
            queued.setDispatchedAt(Instant.now());
            messageLogRepository.save(queued);

            // Track dispatch time for rate limiting
            target.setLastDispatchedAt(Instant.now());
            deviceRepository.save(target);

            if (queued.getRoutingMode() == com.messagingagent.model.RoutingMode.MATRIX) {
                // Instantly enqueue without blocking. The target is properly locked in DB for concurrency protection.
                matrixQueueService.enqueue(target, queued);

            } else {
                SmsInboundEvent event = new SmsInboundEvent();
                event.setCorrelationId(queued.getSmppMessageId());
                event.setSourceAddress(queued.getSourceAddress());
                event.setDestinationAddress(queued.getDestinationAddress());
                event.setMessageText(queued.getMessageText());
                sendSmsToDevice(target, event);
            }

            // Remove from available pool only if the Token Bucket is full
            if (target.getInFlightDispatches() >= Device.MAX_CONCURRENT_DISPATCHES) {
                onlineDevices.remove(target);
            }
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
     * Decrement a device's inFlight Token Bucket and trigger queue drain for the given group.
     * Called by RcsExpirationService when messages expire without a DLR from the APK.
     */
    public void unlockDeviceAndDrainQueue(Device device, DeviceGroup group, String expiredCorrelationId) {
        if (device != null) {
            deviceRepository.decrementInFlight(device.getId());
            int newInFlight = Math.max(0, (device.getInFlightDispatches() != null ? device.getInFlightDispatches() : 0) - 1);
            device.setInFlightDispatches(newInFlight);
            
            log.info("Device {} inFlight decremented after expiration of {}. Current inFlight: {}", device.getName(), expiredCorrelationId, newInFlight);
            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", device.getId(), "inFlightDispatches", newInFlight));
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

    /**
     * Read the latest OTA version from /tmp/updates/apk-meta.txt.
     * The file contains a line like "MessagingAgent-1.0.79.apk".
     * Returns the version string (e.g. "1.0.79") or null if unavailable.
     */
    private String getLatestOtaVersion() {
        try {
            java.io.File metaFile = new java.io.File("/tmp/updates/apk-meta.txt");
            if (!metaFile.exists()) return null;
            String content = new String(java.nio.file.Files.readAllBytes(metaFile.toPath())).trim();
            // Parse: "MessagingAgent-1.0.79.apk" → "1.0.79"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(content);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
