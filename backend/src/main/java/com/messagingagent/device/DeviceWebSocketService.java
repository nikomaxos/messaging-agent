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

    /** Send an SMS dispatch command to a specific device via its STOMP user-queue. */
    public void sendSmsToDevice(Device device, SmsInboundEvent event) {
        String destination = "/queue/sms." + device.getId();
        messagingTemplate.convertAndSend(destination, event);
        log.debug("Sent SMS dispatch to device {} via WebSocket", device.getId());
    }

    /** Called when a device reports a heartbeat via WebSocket. */
    public void handleHeartbeat(String deviceToken, DeviceHeartbeat heartbeat) {
        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            device.setBatteryPercent(heartbeat.getBatteryPercent());
            device.setWifiSignalDbm(heartbeat.getWifiSignalDbm());
            device.setGsmSignalDbm(heartbeat.getGsmSignalDbm());
            device.setGsmSignalAsu(heartbeat.getGsmSignalAsu());
            device.setNetworkOperator(heartbeat.getNetworkOperator());
            device.setRcsCapable(heartbeat.getRcsCapable());
            device.setActiveNetworkType(heartbeat.getActiveNetworkType());
            device.setStatus(Device.Status.ONLINE);
            device.setLastHeartbeat(Instant.now());
            deviceRepository.save(device);
            log.debug("Heartbeat from device {}: battery={}% wifi={}dBm gsm={}dBm network={}",
                    device.getName(), heartbeat.getBatteryPercent(),
                    heartbeat.getWifiSignalDbm(), heartbeat.getGsmSignalDbm(),
                    heartbeat.getActiveNetworkType());
            
            messagingTemplate.convertAndSend("/topic/devices",
                java.util.Map.of(
                    "id", device.getId(),
                    "status", "ONLINE",
                    "batteryPercent", heartbeat.getBatteryPercent() != null ? heartbeat.getBatteryPercent() : "",
                    "wifiSignalDbm", heartbeat.getWifiSignalDbm() != null ? heartbeat.getWifiSignalDbm() : "",
                    "gsmSignalDbm", heartbeat.getGsmSignalDbm() != null ? heartbeat.getGsmSignalDbm() : "",
                    "activeNetworkType", heartbeat.getActiveNetworkType() != null ? heartbeat.getActiveNetworkType() : "",
                    "lastHeartbeat", device.getLastHeartbeat().toString()
                )
            );
        });
    }

    /** Called when a device reports delivery result. Forwards to Kafka. */
    public void handleDeliveryResult(SmsDeliveryResultEvent result) {
        deliveryResultKafka.send("sms.delivery.result", result.getCorrelationId(), result);
    }
}
