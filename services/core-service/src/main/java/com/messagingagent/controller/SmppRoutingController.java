package com.messagingagent.controller;

import com.messagingagent.dto.SmppRoutingDto;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.model.SmppRoutingDestination;
import com.messagingagent.model.RoutingMode;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.repository.CountryPrefixRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/smpp/routings")
@RequiredArgsConstructor
public class SmppRoutingController {

    private final SmppRoutingRepository repository;
    private final SmppClientRepository smppClientRepository;
    private final DeviceGroupRepository deviceGroupRepository;
    private final SmscSupplierRepository smscSupplierRepository;
    private final CountryPrefixRepository countryPrefixRepository;

    @GetMapping
    public List<SmppRoutingDto> getAll() {
        return repository.findAll().stream()
                .map(SmppRoutingDto::fromEntity)
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SmppRoutingDto payload) {
        System.out.println("DEBUG: payload received: " + payload);
        var client = smppClientRepository.findById(payload.getSmppClientId());
        if (client.isEmpty()) {
            System.out.println("DEBUG: client is empty for id: " + payload.getSmppClientId());
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
        
        routing.setEmulateDelivery(payload.isEmulateDelivery());
        routing.setEmulatedErrorCode(payload.getEmulatedErrorCode());
        
        routing.setLoadBalancerEnabled(payload.isLoadBalancerEnabled());
        routing.setResendEnabled(payload.isResendEnabled());
        routing.setResendTrigger(payload.getResendTrigger());
        routing.setRcsExpirationSeconds(payload.getRcsExpirationSeconds());

        if (payload.getFallbackSmscId() != null) {
            smscSupplierRepository.findById(payload.getFallbackSmscId()).ifPresent(routing::setFallbackSmsc);
        } else {
            routing.setFallbackSmsc(null);
        }

        if (payload.getCountryPrefix() != null && payload.getCountryPrefix().getId() != null) {
            countryPrefixRepository.findById(payload.getCountryPrefix().getId()).ifPresent(routing::setCountryPrefix);
        } else {
            routing.setCountryPrefix(null);
        }

        if (payload.getDestinations() != null) {
            List<SmppRouting> allRoutings = repository.findAll();
            for (SmppRoutingDto.DestinationDto dto : payload.getDestinations()) {
                SmppRoutingDestination dest = new SmppRoutingDestination();
                dest.setSmppRouting(routing);
                dest.setWeightPercent(dto.getWeightPercent());

                if (dto.getFallbackSmscId() != null) {
                    smscSupplierRepository.findById(dto.getFallbackSmscId()).ifPresent(dest::setFallbackSmsc);
                }

                if (routing.getRoutingMode() == RoutingMode.SMS) {
                    if (dest.getFallbackSmsc() == null) {
                        System.out.println("DEBUG: dest.getFallbackSmsc() is null for dto: " + dto);
                        return ResponseEntity.badRequest().body(java.util.Map.of("message", "Target SMSC Supplier is required for SMS destinations"));
                    }
                    routing.getDestinations().add(dest);
                } else {
                    if (dto.getDeviceGroupId() == null) {
                        System.out.println("DEBUG: dto.getDeviceGroupId() is null for dto: " + dto);
                        return ResponseEntity.badRequest().body(java.util.Map.of("message", "Device Group is required for non-SMS destinations"));
                    }
                    var group = deviceGroupRepository.findById(dto.getDeviceGroupId());
                    if (group.isPresent()) {
                        // Fail-safe: permit only one routing strategy per device (via its group)
                        for (SmppRouting existing : allRoutings) {
                            if (existing.getId().equals(routing.getId())) continue;
                            boolean hasGroup = existing.getDestinations().stream()
                                .filter(d -> d.getDeviceGroup() != null)
                                .anyMatch(d -> d.getDeviceGroup().getId().equals(group.get().getId()));
                            if (hasGroup && existing.getRoutingMode() != routing.getRoutingMode()) {
                                return ResponseEntity.badRequest().body(java.util.Map.of("message", "Device Group '" + group.get().getName() + "' is already assigned to a route with " + existing.getRoutingMode() + " strategy. A device can only have one routing strategy at a time."));
                            }
                        }
                        dest.setDeviceGroup(group.get());
                        routing.getDestinations().add(dest);
                    }
                }
            }
        }

        try {
            return ResponseEntity.ok(SmppRoutingDto.fromEntity(repository.save(routing)));
        } catch (Exception e) {
            System.out.println("DEBUG: Exception during save: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SmppRoutingDto payload) {
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
