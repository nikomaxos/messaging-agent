package com.messagingagent.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CveScannerService {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String OSV_API_URL = "https://api.osv.dev/v1/query";

    // Core packages to monitor
    private final List<String> packagesToScan = List.of(
            "org.springframework.boot:spring-boot-starter-web",
            "org.springframework.security:spring-security-core",
            "org.apache.kafka:kafka-clients",
            "io.netty:netty-all",
            "org.postgresql:postgresql",
            "com.fasterxml.jackson.core:jackson-databind"
    );

    // Runs once a day at 12:00 PM (or every 24 hours depending on timezone)
    // The user requested at least once a month, but daily is safer.
    @Scheduled(cron = "0 0 12 * * ?")
    public void scanForVulnerabilities() {
        log.info("Starting scheduled CVE scan using OSV API...");
        for (String pkg : packagesToScan) {
            scanPackage(pkg);
        }
    }
    
    // Manual trigger for testing
    public void manualScan() {
        log.info("Starting manual CVE scan...");
        for (String pkg : packagesToScan) {
            scanPackage(pkg);
        }
    }

    private void scanPackage(String pkgName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String requestBody = """
                {
                  "package": {
                    "name": "%s",
                    "ecosystem": "Maven"
                  }
                }
                """.formatted(pkgName);

            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(OSV_API_URL, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode vulns = root.get("vulns");
                if (vulns != null && vulns.isArray()) {
                    for (JsonNode vuln : vulns) {
                        processVulnerability(pkgName, vuln);
                    }
                } else {
                    log.debug("No vulnerabilities found for package: {}", pkgName);
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan package {}: {}", pkgName, e.getMessage());
        }
    }

    private void processVulnerability(String pkgName, JsonNode vuln) {
        String cveId = vuln.has("id") ? vuln.get("id").asText() : "UNKNOWN";
        String summary = vuln.has("summary") ? vuln.get("summary").asText() : "No summary available";

        // Check if we've already seen and alerted for this CVE
        String redisKey = "security:cve:seen:" + cveId;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "true");

        if (Boolean.TRUE.equals(isNew)) {
            log.warn("🚨 NEW VULNERABILITY DETECTED: {} in {}. Summary: {}", cveId, pkgName, summary);
            dispatchActionableNotification(pkgName, cveId, summary);
        }
    }

    private void dispatchActionableNotification(String pkgName, String cveId, String summary) {
        try {
            Map<String, Object> payload = Map.of(
                "title", "🚨 Security Vulnerability Detected: " + cveId,
                "message", String.format("A new vulnerability affecting %s was found. Summary: %s. Do you want me to proceed with improving the system?", pkgName, summary),
                "type", "SECURITY_ALERT",
                "actions", List.of(
                    Map.of(
                        "label", "🤖 Auto-Patch via AI",
                        "url", "http://ai-service:8086/api/ai-agent/tasks/auto-patch",
                        "method", "POST",
                        "body", Map.of("cve", cveId, "package", pkgName, "summary", summary)
                    ),
                    Map.of(
                        "label", "🙈 Ignore",
                        "url", "http://security-service:8087/api/cve/ignore",
                        "method", "POST",
                        "body", Map.of("cve", cveId)
                    )
                )
            );

            // Send to core-service notifications engine via Kafka
            kafkaTemplate.send("notifications.alerts", objectMapper.writeValueAsString(payload));
            log.info("Dispatched actionable notification to Kafka for {}", cveId);

        } catch (Exception e) {
            log.error("Failed to dispatch notification for {}: {}", cveId, e.getMessage());
        }
    }
}
