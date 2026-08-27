package com.messagingagent.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class SecurityEventListener {

    @Value("${app.reporting.url}")
    private String reportingUrl;

    @Value("${app.notifications.url}")
    private String notificationsUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @KafkaListener(topics = "security.events", groupId = "security-service-group")
    public void handleSecurityEvent(String eventJson) {
        log.warn("Received Security Event: {}", eventJson);
        
        try {
            // Forward to Reporting Engine
            log.info("Forwarding event to Reporting Engine: {}", reportingUrl);
            // restTemplate.postForEntity(reportingUrl, eventJson, String.class);
            
            // Forward to Notification Engine for alerts
            log.info("Dispatching alert via Notification Engine: {}", notificationsUrl);
            // restTemplate.postForEntity(notificationsUrl, eventJson, String.class);
            
        } catch (Exception e) {
            log.error("Failed to process security event", e);
        }
    }
}
