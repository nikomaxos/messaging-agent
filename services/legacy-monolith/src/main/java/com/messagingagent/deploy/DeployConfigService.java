package com.messagingagent.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DeployConfigService {

    private final RestTemplate restTemplate = new RestTemplate();

    // In-memory config store (persisted across restarts via the deploy-agent's env vars)
    private final Map<String, Object> config = new ConcurrentHashMap<>();

    @Value("${deploy.agent.url:http://ma-deploy-agent:9000}")
    private String deployAgentUrl;

    public DeployConfigService(
            @Value("${deploy.enabled:false}") boolean enabled,
            @Value("${deploy.branch:main}") String branch,
            @Value("${deploy.cooldown:60}") int cooldown,
            @Value("${deploy.auto-deploy:true}") boolean autoDeploy,
            @Value("${deploy.services:backend admin-panel}") String services,
            @Value("${deploy.webhook-secret:}") String webhookSecret
    ) {
        config.put("enabled", enabled);
        config.put("branch", branch);
        config.put("cooldown", cooldown);
        config.put("autoDeploy", autoDeploy);
        config.put("services", services);
        config.put("webhookSecret", webhookSecret.isEmpty() ? "" : maskSecret(webhookSecret));
        config.put("webhookUrl", "/webhook");
        log.info("DeployConfigService initialized: enabled={}, branch={}, autoDeploy={}", enabled, branch, autoDeploy);
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> result = new LinkedHashMap<>(config);
        // Include deploy agent connectivity
        result.put("agentReachable", isAgentReachable());
        return result;
    }

    public Map<String, Object> updateConfig(Map<String, Object> updates) {
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            // Don't allow overwriting sensitive computed fields
            if ("agentReachable".equals(key) || "webhookUrl".equals(key)) continue;

            if ("webhookSecret".equals(key)) {
                String val = String.valueOf(entry.getValue());
                if (!val.contains("***")) {
                    // Only update if not masked value
                    config.put(key, maskSecret(val));
                }
            } else {
                config.put(key, entry.getValue());
            }
        }
        log.info("Deploy config updated: {}", config);
        return getConfig();
    }

    public Map<String, Object> getDeployAgentStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            JsonNode status = restTemplate.getForObject(deployAgentUrl + "/status", JsonNode.class);
            if (status != null) {
                result.put("connected", true);
                result.put("branch", status.path("branch").asText());
                result.put("deployCount", status.path("deploy_count").asInt());
                result.put("lastDeploy", status.path("last_deploy"));
                result.put("recentDeploys", status.path("recent_deploys"));
            }
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", "Deploy agent not reachable: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> triggerManualDeploy() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // The deploy agent has a /trigger endpoint for manual deploys
            JsonNode response = restTemplate.postForObject(deployAgentUrl + "/trigger", null, JsonNode.class);
            result.put("triggered", true);
            result.put("response", response);
        } catch (Exception e) {
            result.put("triggered", false);
            result.put("error", "Failed to trigger deploy: " + e.getMessage());
        }
        return result;
    }

    private boolean isAgentReachable() {
        try {
            restTemplate.getForObject(deployAgentUrl + "/health", String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.length() < 8) return "***";
        return secret.substring(0, 4) + "***" + secret.substring(secret.length() - 4);
    }
}
