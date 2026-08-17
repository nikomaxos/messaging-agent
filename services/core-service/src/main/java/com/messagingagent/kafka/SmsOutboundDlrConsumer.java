package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsOutboundDlrConsumer {

    private final ObjectMapper objectMapper;
    private final MessageLogRepository messageLogRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "sms.outbound.dlr", groupId = "core-service-dlr-group")
    public void consumeOutboundDlr(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String supplierMessageId = (String) event.get("messageId");
            String statusStr = (String) event.get("status");
            String reason = (String) event.get("reason");

            if (supplierMessageId == null || statusStr == null) {
                log.warn("Invalid outbound DLR event: {}", messageJson);
                return;
            }

            Optional<MessageLog> messageLogOpt = messageLogRepository.findBySupplierMessageId(supplierMessageId);
            if (messageLogOpt.isEmpty()) {
                log.warn("Received DLR for unknown supplierMessageId: {}", supplierMessageId);
                return;
            }

            MessageLog messageLog = messageLogOpt.get();
            
            // Only update if not already final
            if (messageLog.getStatus() == MessageLog.Status.DELIVERED) {
                log.info("Message {} already delivered, ignoring duplicate DLR", messageLog.getId());
                return;
            }

            if ("DELIVERED".equalsIgnoreCase(statusStr)) {
                messageLog.setStatus(MessageLog.Status.DELIVERED);
                messageLog.setFallbackDlrReceivedAt(Instant.now()); // Using fallback timestamp for supplier DLRs
            } else if ("FAILED".equalsIgnoreCase(statusStr)) {
                messageLog.setStatus(MessageLog.Status.FAILED);
                messageLog.setFallbackDlrReceivedAt(Instant.now());
                if (reason != null && !reason.isEmpty()) {
                    messageLog.setErrorDetail(reason);
                }
            }

            messageLogRepository.save(messageLog);
            log.info("Processed DLR for MessageLog id={}, status={}", messageLog.getId(), statusStr);

            // Forward DLR to the ESME client (smpp-edge uses sms.delivery.receipt)
            if (messageLog.getSmppMessageId() != null) {
                String errorPart = "";
                if (reason != null && !reason.isEmpty()) {
                    errorPart = String.format(", \"error\":\"%s\"", reason);
                }
                String clientDlrJson = String.format("{\"correlationId\":\"%s\", \"status\":\"%s\"%s}", 
                        messageLog.getSmppMessageId(), statusStr, errorPart);
                kafkaTemplate.send("sms.delivery.receipt", messageLog.getSmppMessageId(), clientDlrJson);
            }

        } catch (Exception e) {
            log.error("Error processing outbound DLR message", e);
        }
    }
}
