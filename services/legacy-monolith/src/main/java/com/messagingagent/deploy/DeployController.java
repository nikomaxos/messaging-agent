package com.messagingagent.deploy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/deploy")
@RequiredArgsConstructor
@Slf4j
public class DeployController {

    private final DeployConfigService configService;

    // ── Configuration ───────────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(configService.updateConfig(updates));
    }

    // ── Deploy Status (proxied from deploy-agent) ───────────────────

    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(configService.getDeployAgentStatus());
    }

    // ── Manual Trigger ──────────────────────────────────────────────

    @PostMapping("/trigger")
    public ResponseEntity<?> triggerDeploy() {
        return ResponseEntity.ok(configService.triggerManualDeploy());
    }
}
