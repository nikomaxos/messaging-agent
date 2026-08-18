package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsOutboundStatusConsumer {

    private final ObjectMapper objectMapper;
    private final MessageLogRepository messageLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "sms.outbound.status", groupId = "core-service-status-group")
    public void consumeOutboundStatus(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String correlationId = (String) event.get("correlationId");
            String statusStr = (String) event.get("status");
            String supplierMessageId = (String) event.get("supplierMessageId");

            if (correlationId == null || statusStr == null) {
                log.warn("Invalid outbound status event: {}", messageJson);
                return;
            }

            // In our system, correlationId corresponds to smppMessageId (or customerMessageId)
            Optional<MessageLog> messageLogOpt = messageLogRepository.findBySmppMessageId(correlationId);
            if (messageLogOpt.isEmpty()) {
                log.warn("Received status for unknown correlationId: {}", correlationId);
                return;
            }

            MessageLog messageLog = messageLogOpt.get();
            
            if ("DISPATCHED".equalsIgnoreCase(statusStr)) {
                messageLog.setStatus(MessageLog.Status.DISPATCHED);
                if (supplierMessageId != null) {
                    messageLog.setSupplierMessageId(supplierMessageId);
                }
            } else if ("FAILED".equalsIgnoreCase(statusStr)) {
                messageLog.setStatus(MessageLog.Status.FAILED);
                messageLog.setErrorDetail("Failed to submit to upstream SMSC");
                
                // Publish DLR back to client for immediate failure
                String dlrJson = String.format("{\"correlationId\":\"%s\", \"status\":\"FAILED\", \"reason\":\"034\"}", 
                        correlationId);
                kafkaTemplate.send("sms.delivery.receipt", correlationId, dlrJson);
            }

            messageLogRepository.save(messageLog);
            log.info("Processed outbound status for MessageLog id={}, status={}", messageLog.getId(), statusStr);

        } catch (Exception e) {
            log.error("Error processing outbound status message", e);
        }
    }
}
