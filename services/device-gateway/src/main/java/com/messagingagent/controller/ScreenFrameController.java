package com.messagingagent.controller;

import com.messagingagent.device.ScreenFrameService;
import com.messagingagent.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP endpoints for remote desktop screen streaming.
 *
 * POST /api/screen-frame?token={deviceToken}  — device uploads JPEG frame (no JWT, token-auth)
 * GET  /api/devices/{id}/screen-frame          — admin fetches latest JPEG frame (JWT-auth)
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ScreenFrameController {

    private final ScreenFrameService screenFrameService;
    private final DeviceRepository deviceRepository;

    /**
     * Device uploads a JPEG screen frame.
     * Authenticated by deviceToken query parameter (no JWT needed).
     */
    @PostMapping(value = "/api/screen-frame", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadFrame(
            @RequestParam("token") String deviceToken,
            @RequestBody byte[] jpegBytes) {
        return deviceRepository.findByRegistrationToken(deviceToken)
                .map(device -> {
                    screenFrameService.storeFrame(device.getId(), jpegBytes);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.status(403).build());
    }

    /**
     * Admin panel fetches the latest screen frame for a device.
     */
    @GetMapping(value = "/api/devices/{deviceId}/screen-frame", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getFrame(@PathVariable Long deviceId) {
        byte[] frame = screenFrameService.getLatestFrame(deviceId);
        if (frame == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header("Cache-Control", "no-cache, no-store")
                .body(frame);
    }
}
