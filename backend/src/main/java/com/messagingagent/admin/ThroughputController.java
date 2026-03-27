package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Per-SMSC and per-device throughput metrics for different time windows,
 * plus a live TPS (transactions-per-second) feed for real-time monitoring.
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

    /**
     * Live TPS endpoint: returns per-second counts for the last N minutes
     * and computed TPS rates for 1s, 10s, 60s, 5m windows.
     */
    @GetMapping("/live")
    public Map<String, Object> getLiveTps(@RequestParam(defaultValue = "5") int minutes) {
        Instant since = Instant.now().minusSeconds(minutes * 60L);
        Instant now = Instant.now();

        // Time-series: per-second counts
        List<Object[]> raw = messageLogRepository.countPerSecondSince(java.sql.Timestamp.from(since));
        List<Map<String, Object>> timeSeries = new ArrayList<>();
        for (Object[] row : raw) {
            Timestamp ts = (Timestamp) row[0];
            long cnt = ((Number) row[1]).longValue();
            timeSeries.add(Map.of("ts", ts.toInstant().toString(), "count", cnt));
        }

        // Compute TPS rates for different windows
        long last1s  = messageLogRepository.countTerminalAfter(now.minusSeconds(1));
        long last10s = messageLogRepository.countTerminalAfter(now.minusSeconds(10));
        long last60s = messageLogRepository.countTerminalAfter(now.minusSeconds(60));
        long last5m  = messageLogRepository.countTerminalAfter(now.minusSeconds(300));

        Map<String, Object> tps = new LinkedHashMap<>();
        tps.put("last1s", last1s);
        tps.put("last10s", Math.round(last10s / 10.0 * 100.0) / 100.0);
        tps.put("last60s", Math.round(last60s / 60.0 * 100.0) / 100.0);
        tps.put("last5m",  Math.round(last5m / 300.0 * 100.0) / 100.0);
        tps.put("total5m", last5m);

        return Map.of("timeSeries", timeSeries, "tps", tps);
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
