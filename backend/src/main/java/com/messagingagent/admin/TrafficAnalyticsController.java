package com.messagingagent.admin;

import com.messagingagent.service.TrafficAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * BI analytics endpoints — grouped traffic, spam detection, AIT detection.
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class TrafficAnalyticsController {

    private final TrafficAnalyticsService analyticsService;

    @GetMapping("/by-sender")
    public List<Map<String, Object>> bySender(
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(defaultValue = "100") int limit) {
        return analyticsService.bySender(parseWindow(window), limit);
    }

    @GetMapping("/by-content")
    public List<Map<String, Object>> byContent(
            @RequestParam(defaultValue = "24h") String window,
            @RequestParam(defaultValue = "100") int limit) {
        return analyticsService.byContent(parseWindow(window), limit);
    }

    @GetMapping("/spam-suspects")
    public List<Map<String, Object>> spamSuspects(
            @RequestParam(defaultValue = "24h") String window) {
        return analyticsService.spamSuspects(parseWindow(window));
    }

    @GetMapping("/ait-suspects")
    public List<Map<String, Object>> aitSuspects(
            @RequestParam(defaultValue = "24h") String window) {
        return analyticsService.aitSuspects(parseWindow(window));
    }

    private Instant parseWindow(String window) {
        long seconds = switch (window) {
            case "1h" -> 3600;
            case "24h" -> 86400;
            case "7d" -> 604800;
            case "30d" -> 2592000;
            default -> 86400;
        };
        return Instant.now().minusSeconds(seconds);
    }
}
