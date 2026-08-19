package com.messagingagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FallbackScheduler {

    private final MessageLogRepository logRepository;
    private final SmppRoutingRepository routingRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRate = 10000) // Run every 10 seconds
    public void processFallbacks() {
        // 1. Process Timeout Fallbacks (rcsExpiresAt < now)
        List<MessageLog> expiredLogs = logRepository.findExpiredLogs(MessageLog.Status.DISPATCHED, Instant.now());
        for (MessageLog logEntry : expiredLogs) {
            triggerFallback(logEntry, "TIMEOUT");
        }

        // 2. Process Undelivered Fallbacks
        // (Assuming you might want to fetch FAILED/UNDELIVERED messages that haven't been fallback'd yet)
        List<MessageLog> failedLogs = logRepository.findStaleDispatched(Instant.now().minusSeconds(3600)); // Just a safety net
        // If we want to actively poll for UNDELIVERED, we need a query for it.
        // For now, the user requested "Trigger Conditions" which might be checked asynchronously.
    }

    public void triggerFallback(MessageLog originalLog, String reason) {
        try {
            if (originalLog.getDispatchAttempts() >= 1) {
                log.info("Message {} reached max retries, skipping fallback.", originalLog.getId());
                return;
            }
            if (originalLog.getFallbackStartedAt() != null) {
                return; // Already processed
            }

            com.messagingagent.model.RoutingMode fallbackMode = originalLog.getFallbackRoutingMode();
            if (fallbackMode == null) fallbackMode = com.messagingagent.model.RoutingMode.SMS;

            SmscSupplier fallbackSmsc = null;
            com.messagingagent.model.DeviceGroup fallbackDeviceGroup = null;

            if (fallbackMode == com.messagingagent.model.RoutingMode.SMS) {
                fallbackSmsc = originalLog.getFallbackSmsc();
                if (fallbackSmsc == null) {
                    log.warn("No fallback SMSC configured for message {}", originalLog.getId());
                    return;
                }
            } else if (fallbackMode == com.messagingagent.model.RoutingMode.WEBSOCKET) {
                fallbackDeviceGroup = originalLog.getFallbackDeviceGroup();
                if (fallbackDeviceGroup == null) {
                    log.warn("No fallback DeviceGroup configured for message {}", originalLog.getId());
                    return;
                }
            }

            // Mark original as processed
            originalLog.setDispatchAttempts(originalLog.getDispatchAttempts() + 1);
            originalLog.setFallbackStartedAt(Instant.now());
            originalLog.setStatus(MessageLog.Status.FAILED); // or whatever status indicates it gave up on primary
            logRepository.save(originalLog);

            // Create retry log
            MessageLog retryLog = new MessageLog();
            retryLog.setSmppMessageId("RETRY-" + UUID.randomUUID().toString());
            retryLog.setSourceAddress(originalLog.getSourceAddress());
            retryLog.setDestinationAddress(originalLog.getDestinationAddress());
            retryLog.setMessageText(originalLog.getMessageText());
            retryLog.setCustomerMessageId(originalLog.getCustomerMessageId());
            retryLog.setSmppClient(originalLog.getSmppClient());
            retryLog.setRoutingMode(fallbackMode);
            retryLog.setEmulated(originalLog.isEmulated());
            retryLog.setParentMessage(originalLog);
            retryLog.setStatus(MessageLog.Status.QUEUED);
            
            if (fallbackSmsc != null) retryLog.setFallbackSmsc(fallbackSmsc);
            if (fallbackDeviceGroup != null) retryLog.setFallbackDeviceGroup(fallbackDeviceGroup);

            logRepository.save(retryLog);

            if (fallbackMode == com.messagingagent.model.RoutingMode.SMS) {
                Map<String, Object> outboundEvent = Map.of(
                        "correlationId", retryLog.getSmppMessageId(),
                        "supplierId", fallbackSmsc.getId(),
                        "sourceAddress", retryLog.getSourceAddress() != null ? retryLog.getSourceAddress() : "",
                        "destinationAddress", retryLog.getDestinationAddress() != null ? retryLog.getDestinationAddress() : "",
                        "messageText", retryLog.getMessageText() != null ? retryLog.getMessageText() : ""
                );
                kafkaTemplate.send("outbound.smpp", retryLog.getSmppMessageId(), objectMapper.writeValueAsString(outboundEvent));
            } else if (fallbackMode == com.messagingagent.model.RoutingMode.WEBSOCKET) {
                Map<String, Object> outboundEvent = Map.of(
                        "correlationId", retryLog.getSmppMessageId(),
                        "deviceGroupId", fallbackDeviceGroup.getId(),
                        "sourceAddress", retryLog.getSourceAddress() != null ? retryLog.getSourceAddress() : "",
                        "destinationAddress", retryLog.getDestinationAddress() != null ? retryLog.getDestinationAddress() : "",
                        "messageText", retryLog.getMessageText() != null ? retryLog.getMessageText() : ""
                );
                kafkaTemplate.send("websocket.outbound.requests", retryLog.getSmppMessageId(), objectMapper.writeValueAsString(outboundEvent));
            }
            log.info("Triggered {} fallback for message {}, new ID: {}", reason, originalLog.getId(), retryLog.getSmppMessageId());

        } catch (Exception e) {
            log.error("Failed to process fallback for message " + originalLog.getId(), e);
        }
    }
}
