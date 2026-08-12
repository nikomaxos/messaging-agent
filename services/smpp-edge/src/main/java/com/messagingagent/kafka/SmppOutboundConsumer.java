package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmppOutboundConsumer {

    private final SmscConnectionManager smscConnectionManager;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "outbound.smpp", groupId = "smpp-edge-group")
    public void consumeOutboundSmpp(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            
            Long supplierId = event.get("supplierId") != null ? Long.parseLong(event.get("supplierId").toString()) : null;
            String source = (String) event.get("sourceAddress");
            String dest = (String) event.get("destinationAddress");
            String text = (String) event.get("messageText");
            String correlationId = (String) event.get("correlationId");

            if (supplierId == null || dest == null || text == null) {
                log.warn("Invalid outbound SMPP event: {}", messageJson);
                return;
            }

            log.info("Dispatching outbound SMS to supplierId={} dest={} correlationId={}", supplierId, dest, correlationId);
            
            String supplierMessageId = smscConnectionManager.submitMessage(supplierId, source, dest, text);
            
            if (supplierMessageId != null) {
                log.info("Successfully submitted SMS to supplierId={}, supplierMessageId={}", supplierId, supplierMessageId);
                // Publish DISPATCHED event
                String statusJson = String.format("{\"correlationId\":\"%s\", \"supplierMessageId\":\"%s\", \"status\":\"DISPATCHED\", \"timestamp\":%d}", 
                    correlationId, supplierMessageId, System.currentTimeMillis());
                kafkaTemplate.send("sms.outbound.status", correlationId, statusJson);
            } else {
                log.error("Failed to submit SMS to supplierId={}", supplierId);
                // Publish FAILED event
                String statusJson = String.format("{\"correlationId\":\"%s\", \"status\":\"FAILED\", \"timestamp\":%d}", 
                    correlationId, System.currentTimeMillis());
                kafkaTemplate.send("sms.outbound.status", correlationId, statusJson);
            }
        } catch (Exception e) {
            log.error("Error processing outbound SMPP message", e);
        }
    }
}
