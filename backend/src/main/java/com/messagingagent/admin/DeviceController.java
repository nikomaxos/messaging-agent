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
    private final SimpMessagingTemplate messagingTemplate;

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
            if (req.getAutoRebootEnabled() != null) {
                device.setAutoRebootEnabled(req.getAutoRebootEnabled());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_AUTO_REBOOT=" + req.getAutoRebootEnabled());
            }
            if (req.getAutoPurge() != null) {
                device.setAutoPurge(req.getAutoPurge());
                messagingTemplate.convertAndSend("/queue/commands." + id, "SET_AUTO_PURGE=" + req.getAutoPurge());
            }
            groupRepository.findById(req.getGroupId()).ifPresent(device::setGroup);
            return ResponseEntity.ok(deviceRepository.save(device));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!deviceRepository.existsById(id)) return ResponseEntity.notFound().build();
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
            }
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class DeviceRequest {
        @NotBlank private String name;
        private String imei;
        private Long groupId;
        private Boolean autoRebootEnabled;
        private String autoPurge;
    }
}
