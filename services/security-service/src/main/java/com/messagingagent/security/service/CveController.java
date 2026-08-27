package com.messagingagent.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cve")
@RequiredArgsConstructor
@Slf4j
public class CveController {

    private final CveScannerService cveScannerService;

    @PostMapping("/scan")
    public ResponseEntity<String> triggerScan() {
        // Trigger asynchronously to avoid blocking
        java.util.concurrent.CompletableFuture.runAsync(cveScannerService::manualScan);
        return ResponseEntity.ok("Scan initiated.");
    }

    @PostMapping("/ignore")
    public ResponseEntity<String> ignoreCve(@RequestBody Map<String, String> payload) {
        String cve = payload.get("cve");
        log.info("Admin chose to ignore CVE: {}", cve);
        // We already marked it as 'seen' in Redis, so it won't alert again anyway.
        return ResponseEntity.ok("Ignored " + cve);
    }
}
