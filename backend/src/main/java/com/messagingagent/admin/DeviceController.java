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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceRepository deviceRepository;
    private final DeviceGroupRepository groupRepository;
    private final com.messagingagent.repository.MessageLogRepository messageLogRepository;
    private final com.messagingagent.repository.DeviceLogRepository deviceLogRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final com.messagingagent.device.DeviceWebSocketService webSocketService;

    @GetMapping
    public List<Device> listAll() {
        return deviceRepository.findAll();
    }

    @GetMapping("/group/{groupId}")
    public List<Device> listByGroup(@PathVariable Long groupId) {
        return groupRepository.findById(groupId)
                .map(deviceRepository::findByGroup)
                .orElse(List.of());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Device> getById(@PathVariable Long id) {
        return deviceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Device> create(@RequestBody @Valid DeviceRequest req) {
        return groupRepository.findById(req.getGroupId()).map(group -> {
            Device device = Device.builder()
                    .name(req.getName())
                    .imei(req.getImei())
                    .group(group)
                    .registrationToken(UUID.randomUUID().toString())
                    .status(Device.Status.OFFLINE)
                    .build();
            return ResponseEntity.ok(deviceRepository.save(device));
        }).orElse(ResponseEntity.badRequest().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Device> update(@PathVariable Long id, @RequestBody @Valid DeviceRequest req) {
        return deviceRepository.findById(id).map(device -> {
            device.setName(req.getName());
            device.setImei(req.getImei());
            if (req.getPhoneNumber() != null) {
                device.setPhoneNumber(req.getPhoneNumber().isBlank() ? null : req.getPhoneNumber().trim());
            }
            if (req.getAutoRebootEnabled() != null) {
                device.setAutoRebootEnabled(req.getAutoRebootEnabled());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_AUTO_REBOOT=" + req.getAutoRebootEnabled());
            }
            if (req.getAutoPurge() != null) {
                device.setAutoPurge(req.getAutoPurge());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_AUTO_PURGE=" + req.getAutoPurge());
            }
            if (req.getSendIntervalSeconds() != null) {
                device.setSendIntervalSeconds(req.getSendIntervalSeconds());
            }
            if (req.getSilentMode() != null) {
                device.setSilentMode(req.getSilentMode());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_SILENT=" + req.getSilentMode());
            }
            if (req.getCallBlockEnabled() != null) {
                device.setCallBlockEnabled(req.getCallBlockEnabled());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_CALL_BLOCK=" + req.getCallBlockEnabled());
            }
            if (req.getSelfHealingEnabled() != null) {
                device.setSelfHealingEnabled(req.getSelfHealingEnabled());
            }
            if (req.getGroupId() == null) {
                device.setGroup(null);
            } else {
                groupRepository.findById(req.getGroupId()).ifPresent(device::setGroup);
            }
            return ResponseEntity.ok(deviceRepository.save(device));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!deviceRepository.existsById(id)) return ResponseEntity.notFound().build();
        deviceLogRepository.deleteByDeviceId(id);
        messageLogRepository.clearDeviceReferences(id);
        deviceRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/command")
    public ResponseEntity<Void> sendCommand(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        return deviceRepository.findById(id).map(device -> {
            String command = payload.get("command");
            if (command != null) {
                messagingTemplate.convertAndSend("/queue/commands." + id, command);
                if ("UPDATE_APK".equals(command)) {
                    device.setApkUpdateStatus("Sent");
                    deviceRepository.save(device);
                    messagingTemplate.convertAndSend("/topic/devices", Map.of(
                            "id", device.getId(),
                            "apkUpdateStatus", "Sent"
                    ));
                }
                if ("PIN_AUTOSTART".equals(command)) {
                    device.setAutostartPinned(true);
                    deviceRepository.save(device);
                    // Create device log entry
                    deviceLogRepository.save(com.messagingagent.model.DeviceLog.builder()
                            .device(device)
                            .level("INFO")
                            .event("AUTOSTART_PINNED")
                            .detail("MIUI autostart protection applied via admin panel")
                            .build());
                    // Broadcast to UI for real-time feedback
                    messagingTemplate.convertAndSend("/topic/devices", Map.of(
                            "id", device.getId(),
                            "autostartPinned", true
                    ));
                }
            }
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Bulk device command — sends the same command to multiple devices */
    @PostMapping("/bulk-command")
    public ResponseEntity<Map<String, Object>> bulkCommand(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Number> deviceIds = (List<Number>) payload.get("deviceIds");
        String command = (String) payload.get("command");
        if (deviceIds == null || deviceIds.isEmpty() || command == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceIds and command required"));
        }
        int sent = 0;
        for (Number idNum : deviceIds) {
            Long id = idNum.longValue();
            if (deviceRepository.existsById(id)) {
                webSocketService.queueCommand(id, command);
                sent++;
            }
        }
        return ResponseEntity.ok(Map.of("sent", sent, "command", command));
    }

    @Data
    public static class DeviceRequest {
        @NotBlank private String name;
        private String imei;
        private String phoneNumber;
        private Long groupId;
        private Boolean autoRebootEnabled;
        private String autoPurge;
        private Double sendIntervalSeconds;
        private Boolean silentMode;
        private Boolean callBlockEnabled;
        private Boolean selfHealingEnabled;
    }
}
