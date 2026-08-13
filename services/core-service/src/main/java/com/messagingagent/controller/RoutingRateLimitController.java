package com.messagingagent.controller;

import com.messagingagent.model.RoutingRateLimit;
import com.messagingagent.repository.RoutingRateLimitRepository;
import com.messagingagent.service.RedisConfigSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routing/rate-limits")
@RequiredArgsConstructor
public class RoutingRateLimitController {

    private final RoutingRateLimitRepository repository;
    private final RedisConfigSyncService syncService;

    @GetMapping
    public List<RoutingRateLimit> getAll() {
        return repository.findAll();
    }

    @PostMapping
    public RoutingRateLimit create(@RequestBody RoutingRateLimit limit) {
        RoutingRateLimit saved = repository.save(limit);
        syncService.syncRateLimit(saved);
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoutingRateLimit> update(@PathVariable Long id, @RequestBody RoutingRateLimit limit) {
        return repository.findById(id).map(existing -> {
            existing.setCustomerProfileId(limit.getCustomerProfileId());
            existing.setCountryCode(limit.getCountryCode());
            existing.setNetworkId(limit.getNetworkId());
            existing.setSupplierId(limit.getSupplierId());
            existing.setSpeedTps(limit.getSpeedTps());
            RoutingRateLimit saved = repository.save(existing);
            syncService.syncRateLimit(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(limit -> {
            repository.delete(limit);
            syncService.deleteRateLimit(limit);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }
}
