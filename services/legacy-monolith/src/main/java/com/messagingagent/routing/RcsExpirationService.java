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
 * but have not received a delivery receipt within the configured timeout
 * window.
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

    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private RcsExpirationService self;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.scheduling.TaskScheduler taskScheduler;

    public void scheduleExpiration(Long messageId, Instant triggerTime) {
        if (triggerTime != null) {
            taskScheduler.schedule(() -> self.processSingleMessage(messageId), triggerTime);
        }
    }

    // Safety net: Run every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void processExpiredMessages() {
        Instant now = Instant.now();
        List<MessageLog> expiredLogs = messageLogRepository.findExpiredLogs(MessageLog.Status.DISPATCHED, now);

        if (expiredLogs.isEmpty()) {
            return;
        }

        log.info("Found {} expired RCS messages to process for fallback", expiredLogs.size());

        for (MessageLog logEntry : expiredLogs) {
            self.processSingleMessage(logEntry.getId());
        }
    }

    public void processSingleMessage(Long messageId) {
        FallbackResult result = self.executeExpirationLogic(messageId);
        if (result != null && result.device != null && result.group != null) {
            deviceWebSocketService.unlockDeviceAndDrainQueue(result.device, result.group, result.correlationId);
        }
    }

    private record FallbackResult(com.messagingagent.model.Device device, com.messagingagent.model.DeviceGroup group,
            String correlationId) {
    }

    @Transactional
    public FallbackResult executeExpirationLogic(Long messageId) {
        MessageLog logEntry = messageLogRepository.findByIdForUpdate(messageId).orElse(null);
        // Ensure it's still DISPATCHED and actually expired
        if (logEntry != null && logEntry.getStatus() == MessageLog.Status.DISPATCHED
                && logEntry.getRcsExpiresAt() != null) {
            if (!Instant.now().isBefore(logEntry.getRcsExpiresAt())) {
                return executeFallback(logEntry);
            }
        }
        return null;
    }

    private FallbackResult executeFallback(MessageLog logEntry) {
        boolean handledByFallback = false;
        com.messagingagent.model.Device oldDevice = logEntry.getDevice();
        com.messagingagent.model.DeviceGroup oldGroup = logEntry.getDeviceGroup();

        if (logEntry.getFallbackSmsc() != null && logEntry.getResendTrigger() != null) {
            boolean shouldResend = "ALL_FAILURES".equalsIgnoreCase(logEntry.getResendTrigger());

            if (shouldResend) {
                log.info("Expiration triggered Fallback SMSC (id={}) for correlationId={}",
                        logEntry.getFallbackSmsc().getId(), logEntry.getSmppMessageId());

                logEntry.setFallbackStartedAt(Instant.now());

                if (logEntry.getDevice() != null) {
                    deviceWebSocketService.sendSysCommand(logEntry.getDevice(),
                            "CANCEL_RCS=" + logEntry.getDestinationAddress());
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
                logEntry.setDeviceGroup(null);
                logEntry.setDevice(null);
                logEntry.setRoutingMode(null);
                handledByFallback = true;
            }
        }

        if (!handledByFallback) {
            log.info("RCS expiration for correlationId={} — no fallback, awaiting DLR from network",
                    logEntry.getSmppMessageId());
            logEntry.setErrorDetail("RCS delivery receipt timed out — awaiting network DLR");
            logEntry.setRcsExpiresAt(null); // Prevent re-processing by next sweep
        }

        messageLogRepository.save(logEntry);

        return new FallbackResult(oldDevice, oldGroup, logEntry.getSmppMessageId());
    }
}
