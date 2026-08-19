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
    private final com.messagingagent.service.FallbackScheduler fallbackScheduler;

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

            if (messageLog.getStatus() == MessageLog.Status.FAILED) {
                boolean triggerFallback = false;
                
                // 1. Check Routing Fallback Error Codes
                if (messageLog.getFallbackErrorCodes() != null) {
                    String[] errorCodes = messageLog.getFallbackErrorCodes().split(",");
                    for (String code : errorCodes) {
                        if (reason != null && reason.contains(code.trim())) {
                            log.info("DLR error matches routing fallback trigger code: {}", code);
                            triggerFallback = true;
                            break;
                        }
                    }
                }
                
                // 2. Check Supplier Custom Error Codes
                if (!triggerFallback && messageLog.getSmscSupplier() != null && messageLog.getSmscSupplier().getTriggerResendErrorCodes() != null) {
                    String[] supplierCodes = messageLog.getSmscSupplier().getTriggerResendErrorCodes().split(",");
                    for (String code : supplierCodes) {
                        if (reason != null && reason.contains(code.trim())) {
                            log.info("DLR error matches supplier resend trigger code: {}", code);
                            triggerFallback = true;
                            break;
                        }
                    }
                }

                if (triggerFallback) {
                    fallbackScheduler.triggerFallback(messageLog, "DLR_ERROR_MATCH");
                }
            }

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
