package com.messagingagent.routing;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.smpp.SmppResponseService;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Sweeps for RCS messages that were dispatched to a device
 * but have not received a delivery receipt within the configured timeout window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RcsExpirationService {

    private final MessageLogRepository messageLogRepository;
    private final SmscConnectionManager smscConnectionManager;
    private final SmppResponseService smppResponseService;
    private final com.messagingagent.device.DeviceWebSocketService deviceWebSocketService;
    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    // Run every 10 seconds
    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void processExpiredMessages() {
        Instant now = Instant.now();
        List<MessageLog> expiredLogs = messageLogRepository.findExpiredLogs(MessageLog.Status.DISPATCHED, now);

        if (expiredLogs.isEmpty()) {
            return;
        }

        log.info("Found {} expired RCS messages to process for fallback", expiredLogs.size());

        for (MessageLog logEntry : expiredLogs) {
            // Do NOT set a final status (FAILED/RCS_FAILED) — only real DLRs from the network can do that.
            // The message stays DISPATCHED until the DLR watchdog reports DELIVERED/ERROR.

            boolean handledByFallback = false;

            if (logEntry.getFallbackSmsc() != null && logEntry.getResendTrigger() != null) {
                boolean shouldResend = "ALL_FAILURES".equalsIgnoreCase(logEntry.getResendTrigger());

                if (shouldResend) {
                    log.info("Expiration triggered Fallback SMSC (id={}) for correlationId={}",
                            logEntry.getFallbackSmsc().getId(), logEntry.getSmppMessageId());
                    
                    logEntry.setFallbackStartedAt(Instant.now());

                    if (logEntry.getDevice() != null) {
                        deviceWebSocketService.sendSysCommand(logEntry.getDevice(), "CANCEL_RCS=" + logEntry.getDestinationAddress());
                    }

                    com.messagingagent.kafka.SmppOutboundEvent event = com.messagingagent.kafka.SmppOutboundEvent.builder()
                            .messageLogId(logEntry.getId())
                            .supplierId(logEntry.getFallbackSmsc().getId())
                            .sourceAddress(logEntry.getSourceAddress())
                            .destinationAddress(logEntry.getDestinationAddress())
                            .messageText(logEntry.getMessageText())
                            .smppMessageId(logEntry.getSmppMessageId())
                            .build();

                    kafkaTemplate.send("smpp.outbound", event);
                    logEntry.setStatus(MessageLog.Status.QUEUED);
                    handledByFallback = true;
                    // DLR and FallbackMessageId will be set by the SmppOutboundConsumer when processed

                }
            }

            if (!handledByFallback) {
                // No fallback — do NOT send failure DELIVER_SM, do NOT set FAILED.
                // Just mark that expiration happened (set errorDetail for audit) and
                // clear rcsExpiresAt to prevent re-processing. Status stays DISPATCHED.
                log.info("RCS expiration for correlationId={} — no fallback, awaiting DLR from network",
                        logEntry.getSmppMessageId());
                logEntry.setErrorDetail("RCS delivery receipt timed out — awaiting network DLR");
                logEntry.setRcsExpiresAt(null); // Prevent re-processing by next sweep
            }

            messageLogRepository.save(logEntry);
            
            // Unlock the device and trigger queue drain so the next message can be dispatched
            if (logEntry.getDevice() != null && logEntry.getDeviceGroup() != null) {
                deviceWebSocketService.unlockDeviceAndDrainQueue(logEntry.getDevice(), logEntry.getDeviceGroup(), logEntry.getSmppMessageId());
            }
        }
    }
}
