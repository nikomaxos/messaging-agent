package com.messagingagent.admin;

import com.messagingagent.model.DeviceGroup;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.routing.RoundRobinLoadBalancer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class DeviceGroupController {

    private final DeviceGroupRepository groupRepository;
    private final RoundRobinLoadBalancer loadBalancer;

    @GetMapping
    public List<DeviceGroup> listAll() {
        return groupRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceGroup> getById(@PathVariable Long id) {
        return groupRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public DeviceGroup create(@RequestBody @Valid GroupRequest req) {
        DeviceGroup group = DeviceGroup.builder()
                .name(req.getName())
                .description(req.getDescription())
                .active(true)
                .build();
        return groupRepository.save(group);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeviceGroup> update(@PathVariable Long id, @RequestBody @Valid GroupRequest req) {
        return groupRepository.findById(id).map(grp -> {
            grp.setName(req.getName());
            grp.setDescription(req.getDescription());
            grp.setActive(req.isActive());
            if (!req.isActive()) loadBalancer.resetCounter(id);
            return ResponseEntity.ok(groupRepository.save(grp));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!groupRepository.existsById(id)) return ResponseEntity.notFound().build();
        groupRepository.deleteById(id);
        loadBalancer.resetCounter(id);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class GroupRequest {
        @NotBlank private String name;
        private String description;
        private boolean active = true;
    }
}
