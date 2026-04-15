package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.model.SimCard;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.SimCardRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final SimCardRepository simCardRepository;

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

        // Re-use existing device by hardwareId if present, otherwise create new
        Device device = null;
        if (req.getHardwareId() != null && !req.getHardwareId().isBlank()) {
            device = deviceRepository.findByHardwareId(req.getHardwareId().trim()).orElse(null);
        }

        if (device == null) {
            device = Device.builder()
                    .name(req.getDeviceName().trim())
                    .hardwareId(req.getHardwareId().trim())
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

        // Sync SimCards if payload provides them
        if (req.getSimCards() != null) {
            final Device activeDevice = device;
            List<SimCard> updatedSims = new ArrayList<>();
            for (SimData simReq : req.getSimCards()) {
                if (simReq.getIccid() == null || simReq.getIccid().isBlank()) continue;
                
                SimCard sim = simCardRepository.findByIccid(simReq.getIccid().trim()).orElse(new SimCard());
                sim.setIccid(simReq.getIccid().trim());
                sim.setDevice(activeDevice);
                sim.setPhoneNumber(simReq.getPhoneNumber() != null ? simReq.getPhoneNumber().trim() : null);
                sim.setCarrierName(simReq.getCarrierName() != null ? simReq.getCarrierName().trim() : null);
                sim.setImei(simReq.getImei() != null ? simReq.getImei().trim() : null);
                sim.setSlotIndex(simReq.getSlotIndex());
                updatedSims.add(simCardRepository.save(sim));
            }
        }

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
                        d.getLastHeartbeat(),
                        d.getGroup() != null ? d.getGroup().getId() : null,
                        d.getAutoPurge(), d.getLastPurgedAt())))
                .orElse(ResponseEntity.notFound().build());
    }

    public record DeviceStatusResponse(
            Long id, String name, String status,
            java.time.Instant lastHeartbeat, Long groupId,
            String autoPurge, java.time.Instant lastPurgedAt) {}

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @Data
    public static class SimData {
        private String iccid;
        private String phoneNumber;
        private String carrierName;
        private Integer slotIndex;
        private String imei;
    }

    @Data
    public static class RegistrationRequest {
        @NotBlank private String deviceName;
        @NotBlank private String hardwareId;
        private Long groupId;
        private List<SimData> simCards;
    }

    public record RegistrationResponse(Long deviceId, String token, String groupName) {}

    public record GroupSummary(Long id, String name, String description) {}
}
