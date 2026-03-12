package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.DeviceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Unauthenticated endpoint that Android devices call during onboarding.
 *
 * Flow:
 * 1. Android app opens admin panel URL in a browser / calls this API with
 *    device name, IMEI (optional) and desired group ID.
 * 2. Backend creates (or re-registers) the device, generates a registration token.
 * 3. Android stores the token in DataStore and uses it for every WebSocket
 *    connection and heartbeat.
 *
 * Security: this endpoint is included in WebSecurityCustomizer.ignoring()
 * so NO JWT is required.  Rate-limiting / group validation is sufficient
 * protection since the token is useless without a valid WebSocket session.
 */
@RestController
@RequestMapping("/api/devices/register")
@RequiredArgsConstructor
public class DeviceRegistrationController {

    private final DeviceRepository deviceRepository;
    private final DeviceGroupRepository groupRepository;

    /**
     * POST /api/devices/register
     * Body: { "deviceName": "...", "imei": "...", "groupId": 1 }
     * Returns: { "deviceId": 1, "token": "...", "groupName": "..." }
     */
    @PostMapping
    public ResponseEntity<RegistrationResponse> register(
            @RequestBody @Valid RegistrationRequest req) {

        // Validate group exists
        var group = groupRepository.findById(req.getGroupId()).orElse(null);
        if (group == null || !group.isActive()) {
            return ResponseEntity.badRequest().build();
        }

        // Re-use existing device by IMEI if present, otherwise create new
        Device device = null;
        if (req.getImei() != null && !req.getImei().isBlank()) {
            device = deviceRepository.findByImei(req.getImei().trim()).orElse(null);
        }

        if (device == null) {
            device = Device.builder()
                    .name(req.getDeviceName().trim())
                    .imei(req.getImei() != null ? req.getImei().trim() : null)
                    .group(group)
                    .registrationToken(UUID.randomUUID().toString())
                    .status(Device.Status.OFFLINE)
                    .build();
        } else {
            // Re-registration: refresh token and update group/name
            device.setName(req.getDeviceName().trim());
            device.setGroup(group);
            device.setRegistrationToken(UUID.randomUUID().toString());
            device.setStatus(Device.Status.OFFLINE);
        }

        device = deviceRepository.save(device);

        return ResponseEntity.ok(new RegistrationResponse(
                device.getId(),
                device.getRegistrationToken(),
                group.getName()
        ));
    }

    /**
     * GET /api/devices/register/groups
     * Returns active groups the Android device can choose from during onboarding.
     */
    @GetMapping("/groups")
    public ResponseEntity<?> availableGroups() {
        var groups = groupRepository.findAll().stream()
                .filter(com.messagingagent.model.DeviceGroup::isActive)
                .map(g -> new GroupSummary(g.getId(), g.getName(), g.getDescription()))
                .toList();
        return ResponseEntity.ok(groups);
    }

    /**
     * GET /api/devices/register/status/{token}
     * Returns the device's current status using its registration token as auth.
     * This endpoint is unauthenticated so the Android app can poll without a JWT.
     */
    @GetMapping("/status/{token}")
    public ResponseEntity<DeviceStatusResponse> getStatus(@PathVariable String token) {
        return deviceRepository.findByRegistrationToken(token)
                .map(d -> ResponseEntity.ok(new DeviceStatusResponse(
                        d.getId(), d.getName(), d.getStatus().name(),
                        d.getLastHeartbeat(), d.getGroupId())))
                .orElse(ResponseEntity.notFound().build());
    }

    public record DeviceStatusResponse(
            Long id, String name, String status,
            java.time.Instant lastHeartbeat, Long groupId) {}

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @Data
    public static class RegistrationRequest {
        @NotBlank private String deviceName;
        private String imei;                // optional but recommended for re-registration
        private Long groupId;              // which virtual SMSC group to join
    }

    public record RegistrationResponse(Long deviceId, String token, String groupName) {}

    public record GroupSummary(Long id, String name, String description) {}
}
