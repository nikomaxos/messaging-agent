package com.messagingagent.admin;

import com.messagingagent.service.PlatformHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Comprehensive platform health endpoint — Postgres, Kafka, Redis,
 * SMSC connections, device fleet, and message pipeline.
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformHealthController {

    private final PlatformHealthService healthService;

    @GetMapping("/health")
    public Map<String, Object> getPlatformHealth() {
        return healthService.getFullHealth();
    }
}
