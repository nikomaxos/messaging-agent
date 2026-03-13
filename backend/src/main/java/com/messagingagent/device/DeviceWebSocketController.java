package com.messagingagent.device;

import com.messagingagent.kafka.SmsDeliveryResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

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
     * Devices send delivery results to /app/delivery.result.
     */
    @MessageMapping("/delivery.result")
    public void receiveDeliveryResult(@Payload SmsDeliveryResultEvent result,
                                       @Header("deviceToken") String deviceToken) {
        log.info("Delivery result from device token={}: correlationId={} result={}",
                deviceToken, result.getCorrelationId(), result.getResult());
        webSocketService.handleDeliveryResult(result);
    }
}
