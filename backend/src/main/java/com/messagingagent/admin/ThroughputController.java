package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Per-SMSC and per-device throughput metrics for different time windows.
 */
@RestController
@RequestMapping("/api/throughput")
@RequiredArgsConstructor
public class ThroughputController {

    private final MessageLogRepository messageLogRepository;
    private final SmscSupplierRepository smscSupplierRepository;
    private final DeviceRepository deviceRepository;

    @GetMapping
    public Map<String, Object> getThroughput(@RequestParam(defaultValue = "1h") String window) {
        Instant since = Instant.now().minusSeconds(parseWindowSeconds(window));

        // Per-SMSC throughput
        List<Map<String, Object>> smscStats = new ArrayList<>();
        for (SmscSupplier s : smscSupplierRepository.findAll()) {
            long count = messageLogRepository.countBySmscSupplierIdAndCreatedAtAfter(s.getId(), since);
            smscStats.add(Map.of("id", s.getId(), "name", s.getName(), "count", count));
        }

        // Per-device throughput
        List<Map<String, Object>> deviceStats = new ArrayList<>();
        for (Device d : deviceRepository.findAll()) {
            long count = messageLogRepository.countByDeviceIdAndCreatedAtAfter(d.getId(), since);
            deviceStats.add(Map.of("id", d.getId(), "name", d.getName(), "count", count));
        }

        return Map.of("window", window, "smsc", smscStats, "devices", deviceStats);
    }

    private long parseWindowSeconds(String window) {
        return switch (window) {
            case "1h" -> 3600;
            case "24h" -> 86400;
            case "7d" -> 604800;
            default -> 3600;
        };
    }
}
