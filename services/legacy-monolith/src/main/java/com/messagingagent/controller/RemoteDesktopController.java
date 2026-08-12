package com.messagingagent.controller;

import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for remote desktop operations on Android devices.
 * Sends STOMP commands DIRECTLY to the device (instant delivery, no heartbeat wait).
 *
 * Screen streaming: device captures its own screen via root screencap,
 * compresses to JPEG, base64-encodes it, and sends frames back via STOMP.
 * Input: device runs `input tap/swipe/keyevent` locally via root shell.
 * Shell: device runs arbitrary commands via root shell.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/remote")
@RequiredArgsConstructor
@Slf4j
public class RemoteDesktopController {

    private final DeviceRepository deviceRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.messagingagent.device.DeviceWebSocketService webSocketService;

    /**
     * Send a command directly to the device's STOMP command queue.
     * This delivers INSTANTLY (no heartbeat wait) because the device
     * subscribes to /queue/commands.{deviceId} on connect.
     */
    private void sendDirect(Device device, String command) {
        webSocketService.sendSysCommand(device, command);
    }

    /**
     * POST /api/devices/{id}/remote/start
     * Tell the device to start streaming screen frames via STOMP.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startStream(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        log.info("START_SCREEN_STREAM → device {} (direct)", deviceId);
        sendDirect(device, "START_SCREEN_STREAM");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/devices/{id}/remote/stop
     * Tell the device to stop streaming.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopStream(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        log.info("STOP_SCREEN_STREAM → device {} (direct)", deviceId);
        sendDirect(device, "STOP_SCREEN_STREAM");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/devices/{id}/remote/input
     * Send tap, swipe, or key event to device.
     * Body: { "type": "tap"|"swipe"|"key", ... }
     */
    @PostMapping("/input")
    public ResponseEntity<Map<String, String>> sendInput(
            @PathVariable Long deviceId,
            @RequestBody Map<String, Object> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        String type = (String) body.get("type");
        String command;

        switch (type) {
            case "tap":
                command = String.format("INPUT_TAP=%.1f,%.1f,%.1f,%.1f",
                        toDouble(body.get("x")), toDouble(body.get("y")),
                        toDouble(body.get("screenWidth")), toDouble(body.get("screenHeight")));
                break;
            case "swipe":
                command = String.format("INPUT_SWIPE=%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                        toDouble(body.get("x1")), toDouble(body.get("y1")),
                        toDouble(body.get("x2")), toDouble(body.get("y2")),
                        toDouble(body.get("screenWidth")), toDouble(body.get("screenHeight")));
                break;
            case "key":
                command = "INPUT_KEY=" + body.get("keycode");
                break;
            case "long_press":
                command = String.format("INPUT_LONG_PRESS=%.1f,%.1f,%.1f,%.1f",
                        toDouble(body.get("x")), toDouble(body.get("y")),
                        toDouble(body.get("screenWidth")), toDouble(body.get("screenHeight")));
                break;
            case "drag":
                int duration = body.containsKey("duration") ? ((Number) body.get("duration")).intValue() : 2000;
                command = String.format("INPUT_DRAG=%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%d",
                        toDouble(body.get("x1")), toDouble(body.get("y1")),
                        toDouble(body.get("x2")), toDouble(body.get("y2")),
                        toDouble(body.get("screenWidth")), toDouble(body.get("screenHeight")),
                        duration);
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown type: " + type));
        }

        log.info("REMOTE INPUT → device {} (direct): {}", deviceId, command);
        sendDirect(device, command);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/devices/{id}/remote/wake
     * Wake the screen via device-side root shell.
     */
    @PostMapping("/wake")
    public ResponseEntity<Map<String, String>> wakeDevice(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        log.info("WAKE_SCREEN → device {} (direct)", deviceId);
        sendDirect(device, "WAKE_SCREEN");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/devices/{id}/remote/shell
     * Execute a shell command on the device. Output is returned via STOMP /topic/shell/{deviceId}.
     * Body: { "cmd": "ls -la /sdcard" }
     */
    @PostMapping("/shell")
    public ResponseEntity<Map<String, String>> shellExec(
            @PathVariable Long deviceId,
            @RequestBody Map<String, String> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        String cmd = body.get("cmd");
        if (cmd == null || cmd.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cmd is required"));
        }

        log.info("SHELL_EXEC → device {} (direct): {}", deviceId, cmd);
        sendDirect(device, "SHELL_EXEC=" + cmd);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    /**
     * POST /api/devices/{id}/remote/reboot
     * Reboot the device via root shell.
     */
    @PostMapping("/reboot")
    public ResponseEntity<Map<String, String>> rebootDevice(@PathVariable Long deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        log.info("REBOOT → device {} (direct)", deviceId);
        sendDirect(device, "REBOOT");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * POST /api/devices/{id}/remote/command
     * Send an arbitrary STOMP command to the device.
     * Body: { "command": "UPDATE_APK" }
     */
    @PostMapping("/command")
    public ResponseEntity<Map<String, String>> sendCommand(
            @PathVariable Long deviceId,
            @RequestBody Map<String, String> body) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null) return ResponseEntity.notFound().build();

        String command = body.get("command");
        if (command == null || command.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "command is required"));
        }

        log.info("COMMAND → device {} (direct): {}", deviceId, command);
        sendDirect(device, command);
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    private double toDouble(Object val) {
        return val instanceof Number ? ((Number) val).doubleValue() : 0;
    }
}
