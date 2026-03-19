package com.messagingagent.device;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.kafka.SmsInboundEvent;
import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
                // Persist ONLINE event as a device log
                deviceLogRepository.save(DeviceLog.builder()
                        .device(device).level("INFO").event("Device ONLINE")
                        .detail("Reconnected via ping").build());
            }
            deviceRepository.save(device);
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
            device.setApkUpdateStatus(null);
            // Only set ONLINE if device was OFFLINE (reconnected). NEVER override BUSY!
            if (device.getStatus() != Device.Status.BUSY) {
                if (device.getStatus() == Device.Status.OFFLINE) {
                    // Persist ONLINE event as a device log
                    deviceLogRepository.save(DeviceLog.builder()
                            .device(device).level("INFO").event("Device ONLINE")
                            .detail("battery=" + heartbeat.getBatteryPercent() + "% network=" + heartbeat.getActiveNetworkType()).build());
                }
                device.setStatus(Device.Status.ONLINE);
            }
            device.setLastHeartbeat(Instant.now());
            deviceRepository.save(device);
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
            messagingTemplate.convertAndSend("/topic/devices", statusMap);
        });
    }

    /** Called when a device reports delivery result. Forwards to Kafka. */
    public void handleDeliveryResult(SmsDeliveryResultEvent result, String deviceToken) {
        deliveryResultKafka.send("sms.delivery.result", result.getCorrelationId(), result);

        // Unlock the device and trigger queue drain
        if (deviceToken != null) {
            deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
                if (device.getStatus() == Device.Status.BUSY) {
                    device.setStatus(Device.Status.ONLINE);
                    deviceRepository.save(device);
                    log.debug("Device {} unlocked to ONLINE after delivery result", device.getName());
                    
                    messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", device.getId(), "status", "ONLINE"));
                    
                    if (device.getGroup() != null) {
                        drainQueueForGroup(device.getGroup());
                    }
                }
            });
        }
    }

    /** 
     * Drain the message queue for the given DeviceGroup.
     * Synchronized at the DB layer via simple ordering, but this attempts to find the oldest QUEUED message
     * and push it actively to an online device.
     */
    private synchronized void drainQueueForGroup(DeviceGroup group) {
        List<Device> onlineDevices = deviceRepository.findByGroupAndStatus(group, Device.Status.ONLINE);
        if (onlineDevices.isEmpty()) {
            return;
        }
        
        // Find the oldest queued message for this group
        Optional<MessageLog> queuedOpt = messageLogRepository.findFirstByStatusAndDeviceGroupIdOrderByCreatedAtAsc(
                MessageLog.Status.QUEUED, group.getId()
        );

        if (queuedOpt.isPresent()) {
            MessageLog queued = queuedOpt.get();
            Device target = onlineDevices.get(0); // Take the first available

            log.info("Draining QUEUED message id={} to device id={}", queued.getSmppMessageId(), target.getId());

            // Lock device
            target.setStatus(Device.Status.BUSY);
            deviceRepository.save(target);
            messagingTemplate.convertAndSend("/topic/devices", java.util.Map.of("id", target.getId(), "status", "BUSY"));

            // Dispatch message
            queued.setStatus(MessageLog.Status.DISPATCHED);
            queued.setDevice(target);
            messageLogRepository.save(queued);

            SmsInboundEvent event = new SmsInboundEvent();
            event.setCorrelationId(queued.getSmppMessageId());
            event.setSourceAddress(queued.getSourceAddress());
            event.setDestinationAddress(queued.getDestinationAddress());
            event.setMessageText(queued.getMessageText());
            // We ignore systemId here since routing already happened.
            
            sendSmsToDevice(target, event);
            
            // If there's more online devices, we could potentially recursively drain more, but 1-by-1 is safe.
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
