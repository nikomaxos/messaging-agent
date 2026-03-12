package com.messagingagent.admin;

import com.messagingagent.model.SmppConfig;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.SmppConfigRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/smpp-configs")
@RequiredArgsConstructor
public class SmppConfigController {

    private final SmppConfigRepository smppConfigRepository;
    private final DeviceGroupRepository groupRepository;

    @GetMapping
    public List<SmppConfig> listAll() {
        return smppConfigRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SmppConfig> getById(@PathVariable Long id) {
        return smppConfigRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SmppConfig> create(@RequestBody @Valid SmppConfigRequest req) {
        SmppConfig.SmppConfigBuilder builder = SmppConfig.builder()
                .name(req.getName())
                .systemId(req.getSystemId())
                .password(req.getPassword())
                .host(req.getHost())
                .port(req.getPort())
                .bindType(req.getBindType())
                .active(req.isActive());
        if (req.getDeviceGroupId() != null) {
            groupRepository.findById(req.getDeviceGroupId()).ifPresent(builder::deviceGroup);
        }
        return ResponseEntity.ok(smppConfigRepository.save(builder.build()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SmppConfig> update(@PathVariable Long id, @RequestBody @Valid SmppConfigRequest req) {
        return smppConfigRepository.findById(id).map(cfg -> {
            cfg.setName(req.getName());
            cfg.setSystemId(req.getSystemId());
            cfg.setPassword(req.getPassword());
            cfg.setHost(req.getHost());
            cfg.setPort(req.getPort());
            cfg.setBindType(req.getBindType());
            cfg.setActive(req.isActive());
            if (req.getDeviceGroupId() != null) {
                groupRepository.findById(req.getDeviceGroupId()).ifPresent(cfg::setDeviceGroup);
            }
            return ResponseEntity.ok(smppConfigRepository.save(cfg));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!smppConfigRepository.existsById(id)) return ResponseEntity.notFound().build();
        smppConfigRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class SmppConfigRequest {
        @NotBlank private String name;
        @NotBlank @Size(max = 16) private String systemId;
        @NotBlank @Size(max = 9) private String password;
        @NotBlank private String host;
        @Min(1) @Max(65535) private int port;
        private SmppConfig.BindType bindType = SmppConfig.BindType.TRANSCEIVER;
        private boolean active = true;
        private Long deviceGroupId;
    }
}
