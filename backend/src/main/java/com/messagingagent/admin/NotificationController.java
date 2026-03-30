package com.messagingagent.admin;

import com.messagingagent.model.NotificationAlert;
import com.messagingagent.model.NotificationConfig;
import com.messagingagent.repository.NotificationAlertRepository;
import com.messagingagent.repository.NotificationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * CRUD for notification alert configs + alert history retrieval.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
public class NotificationController {

    private final NotificationConfigRepository configRepository;
    private final NotificationAlertRepository alertRepository;

    // ── Notification Configs ──────────────────────────────────────────

    @GetMapping("/configs")
    public List<NotificationConfig> getAllConfigs() {
        return configRepository.findAll();
    }

    @PostMapping("/configs")
    public NotificationConfig createConfig(@RequestBody NotificationConfig config) {
        log.info("Creating notification config: {}", config.getName());
        return configRepository.save(config);
    }

    @PutMapping("/configs/{id}")
    public NotificationConfig updateConfig(@PathVariable Long id, @RequestBody NotificationConfig config) {
        NotificationConfig existing = configRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Config not found"));
        existing.setName(config.getName());
        existing.setEnabled(config.isEnabled());
        existing.setType(config.getType());
        existing.setThreshold(config.getThreshold());
        existing.setCooldownMinutes(config.getCooldownMinutes());
        existing.setChannels(config.getChannels());
        existing.setAlertDeviceGroupId(config.getAlertDeviceGroupId());
        existing.setAlertSmppSupplierId(config.getAlertSmppSupplierId());
        return configRepository.save(existing);
    }

    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id) {
        configRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── Alert History ─────────────────────────────────────────────────

    @GetMapping("/alerts")
    public Page<NotificationAlert> getAlertHistory(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size,
                                                    @RequestParam(required = false) Boolean acknowledged) {
        if (acknowledged != null) {
            return alertRepository.findAllByAcknowledgedOrderByCreatedAtDesc(acknowledged, PageRequest.of(page, size));
        }
        return alertRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    @PatchMapping("/alerts/acknowledge-all")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<java.util.Map<String, Object>> acknowledgeAllAlerts() {
        int updatedCount = alertRepository.acknowledgeAll();
        return ResponseEntity.ok(java.util.Map.of("message", "Acknowledged " + updatedCount + " alerts", "count", updatedCount));
    }

    @PatchMapping("/alerts/{id}/acknowledge")
    public NotificationAlert acknowledgeAlert(@PathVariable Long id) {
        NotificationAlert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found"));
        alert.setAcknowledged(true);
        return alertRepository.save(alert);
    }
}
