package com.messagingagent.controller;

import com.messagingagent.model.SmppRouting;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/smpp/routings")
@RequiredArgsConstructor
public class SmppRoutingController {

    private final SmppRoutingRepository repository;
    private final SmppClientRepository smppClientRepository;
    private final DeviceGroupRepository deviceGroupRepository;

    @GetMapping
    public List<SmppRouting> getAll() {
        return repository.findAll();
    }

    @PostMapping
    public ResponseEntity<SmppRouting> create(@RequestBody Map<String, Object> payload) {
        Long clientId = Long.valueOf(payload.get("smppClientId").toString());
        Long groupId = Long.valueOf(payload.get("deviceGroupId").toString());
        boolean isDefault = Boolean.parseBoolean(payload.getOrDefault("isDefault", "false").toString());

        var client = smppClientRepository.findById(clientId);
        var group = deviceGroupRepository.findById(groupId);

        if (client.isEmpty() || group.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SmppRouting routing = new SmppRouting();
        routing.setSmppClient(client.get());
        routing.setDeviceGroup(group.get());
        routing.setDefault(isDefault);

        return ResponseEntity.ok(repository.save(routing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(routing -> {
            repository.delete(routing);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
