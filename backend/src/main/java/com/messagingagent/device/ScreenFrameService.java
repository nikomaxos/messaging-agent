package com.messagingagent.device;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for the latest screen frame per device.
 * Devices POST JPEG bytes here; admin panel GETs the latest frame.
 */
@Service
public class ScreenFrameService {

    /** deviceId → latest JPEG frame bytes */
    private final ConcurrentHashMap<Long, byte[]> latestFrames = new ConcurrentHashMap<>();

    public void storeFrame(Long deviceId, byte[] jpegBytes) {
        latestFrames.put(deviceId, jpegBytes);
    }

    public byte[] getLatestFrame(Long deviceId) {
        return latestFrames.get(deviceId);
    }

    public void clearFrame(Long deviceId) {
        latestFrames.remove(deviceId);
    }
}
