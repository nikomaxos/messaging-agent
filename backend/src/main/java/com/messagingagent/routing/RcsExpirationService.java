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
            logEntry.setStatus(MessageLog.Status.RCS_FAILED); // Actually transitioning to TIMEOUT/RCS_FAILED

            boolean handledByFallback = false;

            if (logEntry.getFallbackSmsc() != null && logEntry.getResendTrigger() != null) {
                // If the timeout occurred, does it match the ALL_FAILURES or NO_RCS triggers?
                // Depending on the exact wording, "NO_RCS" might just mean the feature is off, 
                // but "ALL_FAILURES" covers expirations too.
                // If user meant "Timeout = NO_RCS", then both should trigger. 
                // We'll trigger for either, since an expiration is effectively a failure.
                boolean shouldResend = "ALL_FAILURES".equalsIgnoreCase(logEntry.getResendTrigger()) || 
                                       "NO_RCS".equalsIgnoreCase(logEntry.getResendTrigger());

                if (shouldResend) {
                    log.info("Expiration triggered Fallback SMSC (id={}) for correlationId={}",
                            logEntry.getFallbackSmsc().getId(), logEntry.getSmppMessageId());
                    
                    logEntry.setFallbackStartedAt(Instant.now());

                    boolean sent = smscConnectionManager.submitMessage(
                            logEntry.getFallbackSmsc().getId(), 
                            logEntry.getSourceAddress(), 
                            logEntry.getDestinationAddress(), 
                            logEntry.getMessageText());
                            
                    if (sent) {
                        logEntry.setStatus(MessageLog.Status.DELIVERED);
                        handledByFallback = true;
                    }
                }
            }

            if (!handledByFallback) {
                // Return a failure to the original SMPP client
                smppResponseService.sendDeliveryFailure(logEntry.getSmppMessageId(), "EXPIRED");
                logEntry.setStatus(MessageLog.Status.FAILED);
            }

            messageLogRepository.save(logEntry);
        }
    }
}
