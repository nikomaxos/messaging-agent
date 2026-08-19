package com.messagingagent.controller;

import com.messagingagent.service.TrafficAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class TrafficAnalyticsController {

    private final TrafficAnalyticsService service;

    private Instant parseWindow(String window) {
        if ("1h".equals(window)) return Instant.now().minus(1, ChronoUnit.HOURS);
        if ("24h".equals(window)) return Instant.now().minus(24, ChronoUnit.HOURS);
        if ("7d".equals(window)) return Instant.now().minus(7, ChronoUnit.DAYS);
        if ("30d".equals(window)) return Instant.now().minus(30, ChronoUnit.DAYS);
        return Instant.now().minus(1, ChronoUnit.HOURS);
    }

    @GetMapping("/by-sender")
    public List<Map<String, Object>> bySender(
            @RequestParam(defaultValue = "1h") String window,
            @RequestParam(defaultValue = "50") int limit) {
        return service.bySender(parseWindow(window), limit);
    }

    @GetMapping("/by-content")
    public List<Map<String, Object>> byContent(
            @RequestParam(defaultValue = "1h") String window,
            @RequestParam(defaultValue = "50") int limit) {
        return service.byContent(parseWindow(window), limit);
    }

    @GetMapping("/spam-suspects")
    public List<Map<String, Object>> spamSuspects(
            @RequestParam(defaultValue = "24h") String window) {
        return service.spamSuspects(parseWindow(window));
    }

    @GetMapping("/ait-suspects")
    public List<Map<String, Object>> aitSuspects(
            @RequestParam(defaultValue = "24h") String window) {
        return service.aitSuspects(parseWindow(window));
    }
}
