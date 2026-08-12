package com.messagingagent.controller;

import com.messagingagent.model.RoutingRateLimit;
import com.messagingagent.repository.RoutingRateLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routing/rate-limits")
@RequiredArgsConstructor
public class RoutingRateLimitController {

    private final RoutingRateLimitRepository repository;

    @GetMapping
    public List<RoutingRateLimit> getAll() {
        return repository.findAll();
    }

    @PostMapping
    public RoutingRateLimit create(@RequestBody RoutingRateLimit limit) {
        return repository.save(limit);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoutingRateLimit> update(@PathVariable Long id, @RequestBody RoutingRateLimit limit) {
        return repository.findById(id).map(existing -> {
            existing.setCustomerProfileId(limit.getCustomerProfileId());
            existing.setCountryCode(limit.getCountryCode());
            existing.setNetworkId(limit.getNetworkId());
            existing.setSupplierId(limit.getSupplierId());
            existing.setSpeedTps(limit.getSpeedTps());
            return ResponseEntity.ok(repository.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
