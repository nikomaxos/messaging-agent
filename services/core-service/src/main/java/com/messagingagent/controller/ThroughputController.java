package com.messagingagent.controller;

import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/throughput")
@RequiredArgsConstructor
public class ThroughputController {

    private final MessageLogRepository messageLogRepository;
    private final SmscSupplierRepository smscSupplierRepository;
    private final DeviceRepository deviceRepository;

    private Instant parseWindow(String window) {
        if ("1h".equals(window)) return Instant.now().minus(1, ChronoUnit.HOURS);
        if ("24h".equals(window)) return Instant.now().minus(24, ChronoUnit.HOURS);
        if ("7d".equals(window)) return Instant.now().minus(7, ChronoUnit.DAYS);
        return Instant.now().minus(1, ChronoUnit.HOURS);
    }

    @GetMapping
    public Map<String, Object> getThroughput(@RequestParam(defaultValue = "1h") String window) {
        Instant after = parseWindow(window);
        
        List<Map<String, Object>> smsc = smscSupplierRepository.findAll().stream().map(s -> {
            long count = messageLogRepository.countBySmscSupplierIdAndCreatedAtAfter(s.getId(), after);
            return Map.<String, Object>of("id", s.getId(), "name", s.getName(), "count", count);
        }).collect(Collectors.toList());

        List<Map<String, Object>> devices = deviceRepository.findAll().stream().map(d -> {
            long count = messageLogRepository.countByDeviceIdAndCreatedAtAfter(d.getId(), after);
            return Map.<String, Object>of("id", d.getId(), "name", d.getName(), "count", count);
        }).collect(Collectors.toList());

        return Map.of("smsc", smsc, "devices", devices);
    }

    @GetMapping("/live")
    public Map<String, Object> getLiveTps(@RequestParam(defaultValue = "5") int minutes) {
        Instant since = Instant.now().minus(minutes, ChronoUnit.MINUTES);
        List<Object[]> raw = messageLogRepository.countPerSecondSince(java.sql.Timestamp.from(since));
        
        List<Map<String, Object>> timeSeries = new ArrayList<>();
        long last1s = 0, sum10s = 0, sum60s = 0, total = 0;
        Instant now = Instant.now();
        
        for (Object[] row : raw) {
            Instant ts = ((java.util.Date) row[0]).toInstant();
            long count = ((Number) row[1]).longValue();
            timeSeries.add(Map.of("ts", ts.toString(), "count", count));
            
            total += count;
            long secondsAgo = ChronoUnit.SECONDS.between(ts, now);
            if (secondsAgo <= 1) last1s = count;
            if (secondsAgo <= 10) sum10s += count;
            if (secondsAgo <= 60) sum60s += count;
        }

        Map<String, Object> tps = Map.of(
            "last1s", last1s,
            "last10s", String.format("%.2f", sum10s / 10.0),
            "last60s", String.format("%.2f", sum60s / 60.0),
            "total5m", total
        );

        return Map.of("tps", tps, "timeSeries", timeSeries);
    }
}
