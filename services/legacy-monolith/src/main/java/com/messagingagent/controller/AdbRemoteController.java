package com.messagingagent.controller;

import com.messagingagent.adb.AdbService;
import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for remote desktop operations on Android devices via ADB.
 * Provides screenshot capture, touch input, swipe, and key events.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}")
public class AdbRemoteController {

    private static final Logger log = LoggerFactory.getLogger(AdbRemoteController.class);

    private final AdbService adbService;
    private final DeviceRepository deviceRepository;

    public AdbRemoteController(AdbService adbService, DeviceRepository deviceRepository) {
        this.adbService = adbService;
        this.deviceRepository = deviceRepository;
    }

    /**
     * GET /api/devices/{id}/screenshot
     * Returns a PNG screenshot of the device screen.
     */
    @GetMapping("/screenshot")
    public ResponseEntity<byte[]> getScreenshot(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] png = adbService.captureScreenshot(device);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setCacheControl("no-store");
            return new ResponseEntity<>(png, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("Screenshot failed for device {}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(("Screenshot failed: " + e.getMessage()).getBytes());
        }
    }

    /**
     * GET /api/devices/{id}/screen-info
     * Returns screen resolution and device info.
     */
    @GetMapping("/screen-info")
    public ResponseEntity<Map<String, Object>> getScreenInfo(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        int[] resolution = adbService.getScreenResolution(device);
        return ResponseEntity.ok(Map.of(
                "width", resolution[0],
                "height", resolution[1],
                "deviceName", device.getName() != null ? device.getName() : "Unknown"
        ));
    }

    /**
     * POST /api/devices/{id}/input/tap
     * Body: { "x": 0.5, "y": 0.3, "screenWidth": 1, "screenHeight": 1 }
     * Coordinates can be normalized (0-1) or pixel-based.
     * They are scaled from the client viewport to actual device resolution.
     */
    @PostMapping("/input/tap")
    public ResponseEntity<Map<String, String>> sendTap(
            @PathVariable Long deviceId,
            @RequestBody Map<String, Object> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            double clientX = ((Number) body.get("x")).doubleValue();
            double clientY = ((Number) body.get("y")).doubleValue();
            double clientW = ((Number) body.get("screenWidth")).doubleValue();
            double clientH = ((Number) body.get("screenHeight")).doubleValue();

            int[] deviceRes = adbService.getScreenResolution(device);
            int devX = (int) (clientX / clientW * deviceRes[0]);
            int devY = (int) (clientY / clientH * deviceRes[1]);

            log.info("TAP device {} → raw({}, {}) / screen({}, {}) → device({}, {}) res={}x{}",
                    deviceId, clientX, clientY, clientW, clientH, devX, devY, deviceRes[0], deviceRes[1]);

            adbService.sendTap(device, devX, devY);
            return ResponseEntity.ok(Map.of("status", "ok", "deviceX", String.valueOf(devX), "deviceY", String.valueOf(devY)));
        } catch (Exception e) {
            log.warn("Tap failed for device {}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * POST /api/devices/{id}/input/swipe
     * Body: { "x1", "y1", "x2", "y2", "duration", "screenWidth", "screenHeight" }
     */
    @PostMapping("/input/swipe")
    public ResponseEntity<Map<String, String>> sendSwipe(
            @PathVariable Long deviceId,
            @RequestBody Map<String, Object> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            double clientW = ((Number) body.get("screenWidth")).doubleValue();
            double clientH = ((Number) body.get("screenHeight")).doubleValue();
            int[] deviceRes = adbService.getScreenResolution(device);

            int x1 = (int) (((Number) body.get("x1")).doubleValue() / clientW * deviceRes[0]);
            int y1 = (int) (((Number) body.get("y1")).doubleValue() / clientH * deviceRes[1]);
            int x2 = (int) (((Number) body.get("x2")).doubleValue() / clientW * deviceRes[0]);
            int y2 = (int) (((Number) body.get("y2")).doubleValue() / clientH * deviceRes[1]);
            int duration = body.containsKey("duration") ? ((Number) body.get("duration")).intValue() : 300;

            log.info("SWIPE device {} → ({},{}) to ({},{}) duration={}ms", deviceId, x1, y1, x2, y2, duration);

            adbService.sendSwipe(device, x1, y1, x2, y2, duration);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.warn("Swipe failed for device {}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * POST /api/devices/{id}/input/keyevent
     * Body: { "keycode": 3 }  (3=Home, 4=Back, 26=Power/Wake, 187=RecentApps)
     */
    @PostMapping("/input/keyevent")
    public ResponseEntity<Map<String, String>> sendKeyEvent(
            @PathVariable Long deviceId,
            @RequestBody Map<String, Integer> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            int keycode = body.getOrDefault("keycode", 0);
            log.info("KEYEVENT device {} → keycode={}", deviceId, keycode);
            adbService.sendKeyEvent(device, keycode);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.warn("KeyEvent failed for device {}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * POST /api/devices/{id}/wake
     * Wake the screen and dismiss lock screen.
     */
    @PostMapping("/wake")
    public ResponseEntity<Map<String, String>> wakeDevice(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            log.info("WAKE device {}", deviceId);
            adbService.wakeAndUnlock(device);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.warn("Wake failed for device {}: {}", deviceId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * POST /api/devices/{id}/adb/connect
     * Manually trigger ADB connect for a device.
     */
    @PostMapping("/adb/connect")
    public ResponseEntity<Map<String, Object>> connectAdb(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.notFound().build();
        }

        boolean connected = adbService.ensureConnected(device);
        log.info("ADB connect device {} → connected={} address={}", deviceId, connected, device.getAdbWifiAddress());
        return ResponseEntity.ok(Map.of(
                "connected", connected,
                "address", device.getAdbWifiAddress() != null ? device.getAdbWifiAddress() : "not set"
        ));
    }
}
