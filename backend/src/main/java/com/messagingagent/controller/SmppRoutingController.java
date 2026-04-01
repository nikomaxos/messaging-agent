package com.messagingagent.controller;

import com.messagingagent.dto.SmppRoutingDto;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.model.SmppRoutingDestination;
import com.messagingagent.model.RoutingMode;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/smpp/routings")
@RequiredArgsConstructor
public class SmppRoutingController {

    private final SmppRoutingRepository repository;
    private final SmppClientRepository smppClientRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final SmscSupplierRepository smscSupplierRepository;

    @GetMapping
    public List<SmppRoutingDto> getAll() {
        return repository.findAll().stream()
                .map(SmppRoutingDto::fromEntity)
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<SmppRoutingDto> create(@RequestBody SmppRoutingDto payload) {
        var client = smppClientRepository.findById(payload.getSmppClientId());
        if (client.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SmppRouting routing = new SmppRouting();
        if (payload.getId() != null) {
            routing = repository.findById(payload.getId()).orElse(routing);
            routing.getDestinations().clear(); // Reset destinations for update
        }

        routing.setSmppClient(client.get());
        routing.setDefault(payload.isDefault());
        
        try {
            routing.setRoutingMode(RoutingMode.valueOf(payload.getRoutingMode()));
        } catch (Exception e) {
            routing.setRoutingMode(RoutingMode.WEBSOCKET);
        }
        routing.setAutoFailEnabled(payload.isAutoFailEnabled());
        routing.setAutoFailTimeoutMinutes(payload.getAutoFailTimeoutMinutes() != 0 ? payload.getAutoFailTimeoutMinutes() : 15);
        
        routing.setLoadBalancerEnabled(payload.isLoadBalancerEnabled());
        routing.setResendEnabled(payload.isResendEnabled());
        routing.setResendTrigger(payload.getResendTrigger());
        routing.setRcsExpirationSeconds(payload.getRcsExpirationSeconds());

        if (payload.getFallbackSmscId() != null) {
            smscSupplierRepository.findById(payload.getFallbackSmscId()).ifPresent(routing::setFallbackSmsc);
        } else {
            routing.setFallbackSmsc(null);
        }

        if (payload.getDestinations() != null) {
            for (SmppRoutingDto.DestinationDto dto : payload.getDestinations()) {
                var group = deviceGroupRepository.findById(dto.getDeviceGroupId());
                if (group.isPresent()) {
                    SmppRoutingDestination dest = new SmppRoutingDestination();
                    dest.setSmppRouting(routing);
                    dest.setDeviceGroup(group.get());
                    dest.setWeightPercent(dto.getWeightPercent());

                    if (dto.getFallbackSmscId() != null) {
                        smscSupplierRepository.findById(dto.getFallbackSmscId()).ifPresent(dest::setFallbackSmsc);
                    }
                    routing.getDestinations().add(dest);
                }
            }
        }

        return ResponseEntity.ok(SmppRoutingDto.fromEntity(repository.save(routing)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SmppRoutingDto> update(@PathVariable Long id, @RequestBody SmppRoutingDto payload) {
        payload.setId(id);
        return create(payload);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(routing -> {
            repository.delete(routing);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
