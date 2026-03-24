package com.messagingagent.routing;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodic re-check for messages stuck in "Dispatched to RCS" state.
 * 
 * Runs every 2 minutes:
 *   - Messages stuck 3–15 min (with rcsSentAt): sends RECHECK_DLR command to the device
 *     so it re-queries bugle_db and reports back any missed delivery reports.
 *   - Messages stuck > 15 min (with rcsSentAt): marks FAILED with "DLR timeout".
 *   - Messages stuck > 5 min (with rcsSentAt=NULL): orphaned dispatches where the device
 *     never acknowledged — marks FAILED. This handles app restarts mid-batch.
 * 
 * This is completely independent from the self-healing mechanism.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DlrRecheckScheduler {

    private final MessageLogRepository messageLogRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final long RECHECK_AFTER_MINUTES = 3;
    private static final long TIMEOUT_AFTER_MINUTES = 15;
    private static final long ORPHAN_TIMEOUT_MINUTES = 5;

    @Scheduled(fixedRate = 120_000) // every 2 min
    public void recheckStuckDlrs() {
        Instant now = Instant.now();
        Instant recheckThreshold = now.minus(RECHECK_AFTER_MINUTES, ChronoUnit.MINUTES);
        Instant timeoutThreshold = now.minus(TIMEOUT_AFTER_MINUTES, ChronoUnit.MINUTES);

        // Find messages stuck in DISPATCHED with rcsSentAt set (= "Dispatched to RCS")
        List<MessageLog> stuck = messageLogRepository.findByStatusAndRcsSentAtIsNotNull(
                MessageLog.Status.DISPATCHED);

        int rechecked = 0;
        int timedOut = 0;

        for (MessageLog msg : stuck) {
            if (msg.getRcsSentAt() == null || msg.getDevice() == null) continue;

            if (msg.getRcsSentAt().isBefore(timeoutThreshold)) {
                // Stuck > 15 min — mark FAILED
                msg.setStatus(MessageLog.Status.FAILED);
                msg.setErrorDetail("DLR timeout — no delivery confirmation after " + TIMEOUT_AFTER_MINUTES + " min");
                messageLogRepository.save(msg);
                timedOut++;
                log.warn("DLR timeout: marking msg id={} (correlationId={}) as FAILED after {}+ min",
                        msg.getId(), msg.getSmppMessageId(), TIMEOUT_AFTER_MINUTES);
            } else if (msg.getRcsSentAt().isBefore(recheckThreshold)) {
                // Stuck 3-15 min — ask device to re-check bugle_db
                String command = "RECHECK_DLR=" + msg.getSmppMessageId();
                messagingTemplate.convertAndSend(
                        "/queue/commands." + msg.getDevice().getId(), command);
                rechecked++;
            }
        }

        if (rechecked > 0 || timedOut > 0) {
            log.info("DLR re-check: sent {} RECHECK_DLR commands, timed out {} messages", rechecked, timedOut);
        }

        // Sweep for ORPHANED dispatches: rcsSentAt IS NULL and dispatchedAt > 5 min ago
        // These are messages that were dispatched to the device but the device never
        // acknowledged them (e.g., app restart mid-batch, crash, lost WebSocket).
        Instant orphanThreshold = now.minus(ORPHAN_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<MessageLog> orphaned = messageLogRepository.findByStatusAndRcsSentAtIsNullAndDispatchedAtBefore(
                MessageLog.Status.DISPATCHED, orphanThreshold);

        int orphanCleaned = 0;
        for (MessageLog msg : orphaned) {
            if (msg.getDispatchedAt() == null) continue;
            msg.setStatus(MessageLog.Status.FAILED);
            msg.setErrorDetail("Orphaned dispatch — device never acknowledged (app restart/crash)");
            messageLogRepository.save(msg);
            orphanCleaned++;
            log.warn("Orphan cleanup: marking msg id={} (correlationId={}) as FAILED — dispatched {}s ago with no SENT acknowledgement",
                    msg.getId(), msg.getSmppMessageId(),
                    java.time.Duration.between(msg.getDispatchedAt(), now).getSeconds());
        }

        if (orphanCleaned > 0) {
            log.info("Orphan cleanup: marked {} orphaned DISPATCHED messages as FAILED", orphanCleaned);
        }
    }
}
