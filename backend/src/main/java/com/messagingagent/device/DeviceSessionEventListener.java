package com.messagingagent.device;

import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for STOMP WebSocket connect/disconnect events.
 *
 * On CONNECT: reads "deviceToken" from STOMP headers → marks device ONLINE
 *             and maps sessionId → deviceToken for the disconnect lookup.
 * On DISCONNECT: uses the sessionId map to find the device and marks it OFFLINE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceSessionEventListener {

    private final DeviceRepository deviceRepository;

    /** Maps STOMP sessionId → deviceToken for disconnect lookup. */
    private final ConcurrentHashMap<String, String> sessionTokenMap = new ConcurrentHashMap<>();

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor sha = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId   = sha.getSessionId();
        String deviceToken = sha.getFirstNativeHeader("deviceToken");

        if (deviceToken == null || deviceToken.isBlank()) {
            log.debug("WebSocket CONNECT without deviceToken (sessionId={})", sessionId);
            return;
        }

        sessionTokenMap.put(sessionId, deviceToken);

        deviceRepository.findByRegistrationToken(deviceToken).ifPresentOrElse(device -> {
            device.setStatus(Device.Status.ONLINE);
            device.setSessionId(sessionId);
            device.setLastHeartbeat(Instant.now());
            deviceRepository.save(device);
            log.info("Device '{}' (id={}) connected — sessionId={}", device.getName(), device.getId(), sessionId);
        }, () -> log.warn("CONNECT from unknown deviceToken={}", deviceToken));
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId   = event.getSessionId();
        String deviceToken = sessionTokenMap.remove(sessionId);

        if (deviceToken == null) return;

        deviceRepository.findByRegistrationToken(deviceToken).ifPresent(device -> {
            device.setStatus(Device.Status.OFFLINE);
            device.setSessionId(null);
            deviceRepository.save(device);
            log.info("Device '{}' (id={}) disconnected — sessionId={}", device.getName(), device.getId(), sessionId);
        });
    }
}
