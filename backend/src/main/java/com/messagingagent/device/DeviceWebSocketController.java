package com.messagingagent.device;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;

/**
 * STOMP WebSocket controller for Android device communications.
 *
 * Devices connect and send messages to these endpoints.
 * Backend pushes SMS to /queue/sms.{deviceId} (user queues).
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class DeviceWebSocketController {

    private final DeviceWebSocketService webSocketService;

    /**
     * Devices send heartbeats to /app/heartbeat.
     * Header "deviceToken" identifies the device.
     */
    @MessageMapping("/heartbeat")
    public void receiveHeartbeat(@Payload DeviceHeartbeat heartbeat,
                                  @Header("deviceToken") String deviceToken) {
        webSocketService.handleHeartbeat(deviceToken, heartbeat);
    }

    /**
     * Lightweight ping — just confirms device is alive and triggers queue drain.
     * No sensor data, minimal overhead. Sent every ~5 seconds.
     */
    @MessageMapping("/ping")
    public void receivePing(@Header("deviceToken") String deviceToken) {
        webSocketService.handlePing(deviceToken);
    }

    /**
     * Devices send delivery results to /app/delivery.result.
     */
    @MessageMapping("/delivery.result")
    public void receiveDeliveryResult(@Payload SmsDeliveryResultEvent result,
                                       @Header("deviceToken") String deviceToken) {
        log.info("Delivery result from device token={}: correlationId={} result={}",
                deviceToken, result.getCorrelationId(), result.getResult());
        webSocketService.handleDeliveryResult(result, deviceToken);
    }

    /**
     * Devices send APK update statuses to /app/apk.status.
     */
    @MessageMapping("/apk.status")
    public void receiveApkStatus(@Payload Map<String, String> payload,
                                 @Header("deviceToken") String deviceToken) {
        String status = payload.get("status");
        if (status != null) {
            webSocketService.handleApkStatus(deviceToken, status);
        }
    }

    /**
     * Devices send batched logs to /app/device.logs on each heartbeat.
     * Payload: JSON array of { level, event, detail } objects.
     */
    @MessageMapping("/device.logs")
    public void receiveDeviceLogs(@Payload java.util.List<Map<String, String>> logs,
                                   @Header("deviceToken") String deviceToken) {
        webSocketService.handleDeviceLogs(deviceToken, logs);
    }
}
