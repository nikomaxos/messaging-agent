package com.messagingagent.routing;

import com.messagingagent.model.MessageLog;
import com.messagingagent.model.Device;
import com.messagingagent.repository.DeviceRepository;
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
 * Runs every 2 minutes for standard WebSocket messages.
 * Runs every 1 minute to broadcast BULK DLR requests for Matrix messages.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DlrRecheckScheduler {

    private final MessageLogRepository messageLogRepository;
    private final DeviceRepository deviceRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final long RECHECK_AFTER_MINUTES = 3;
    private static final long ORPHAN_TIMEOUT_MINUTES = 5;

    @Scheduled(fixedRate = 60_000)
    public void requestBulkMatrixDlrs() {
        List<Device> onlineDevices = deviceRepository.findByStatus(Device.Status.ONLINE);
        for (Device device : onlineDevices) {
            messagingTemplate.convertAndSend("/queue/commands." + device.getId(), "SYNC_MATRIX_BULK_DLR=180");
        }
    }

    @Scheduled(fixedRate = 120_000) // every 2 min
    public void recheckStuckDlrs() {
        Instant now = Instant.now();
        Instant recheckThreshold = now.minus(RECHECK_AFTER_MINUTES, ChronoUnit.MINUTES);

        // Find regular websocket messages stuck in DISPATCHED with rcsSentAt set (= "Dispatched to RCS")
        List<MessageLog> stuck = messageLogRepository.findByStatusAndRcsSentAtIsNotNull(
                MessageLog.Status.DISPATCHED);

        int rechecked = 0;
        int timedOut = 0;

        for (MessageLog msg : stuck) {
            if (msg.getRcsSentAt() == null || msg.getDevice() == null) continue;

            if (msg.getAutoFailExpiresAt() != null && now.isAfter(msg.getAutoFailExpiresAt())) {
                // Auto-fail timeout reached — mark FAILED
                msg.setStatus(MessageLog.Status.FAILED);
                msg.setErrorDetail("DLR auto-fail timeout reached — no delivery confirmation");
                messageLogRepository.save(msg);
                timedOut++;
                log.warn("DLR timeout: marking msg id={} (correlationId={}) as FAILED via Auto-Fail",
                        msg.getId(), msg.getSmppMessageId());
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

        // Sweep for ORPHANED dispatches (rcsSentAt IS NULL) and MATRIX dispatches
        // Matrix messages don't use rcsSentAt, so they will be fetched here.
        List<MessageLog> unacknowledged = messageLogRepository.findByStatusAndRcsSentAtIsNullAndDispatchedAtBefore(
                MessageLog.Status.DISPATCHED, now);

        int orphanCleaned = 0;
        int matrixCleaned = 0;
        
        for (MessageLog msg : unacknowledged) {
            if (msg.getDispatchedAt() == null) continue;
            
            boolean isMatrix = msg.getRoutingMode() == com.messagingagent.model.RoutingMode.MATRIX || (msg.getSupplierMessageId() != null && msg.getSupplierMessageId().startsWith("$"));
            if (isMatrix) {
                if (msg.getAutoFailExpiresAt() != null && now.isAfter(msg.getAutoFailExpiresAt())) {
                    msg.setStatus(MessageLog.Status.FAILED);
                    msg.setErrorDetail("Matrix DLR timeout — no sync response within Auto-Fail window");
                    messageLogRepository.save(msg);
                    matrixCleaned++;
                }
                // We no longer trigger per-message TRACK_MATRIX_DLR, handled via SYNC_MATRIX_BULK_DLR
                continue;
            }

            // If not matrix, evaluate as normal orphan timeout (5 mins)
            Instant orphanThreshold = now.minus(ORPHAN_TIMEOUT_MINUTES, ChronoUnit.MINUTES);
            if (msg.getDispatchedAt().isBefore(orphanThreshold)) {
                msg.setStatus(MessageLog.Status.FAILED);
                msg.setErrorDetail("Orphaned dispatch — device never acknowledged (app restart/crash)");
                messageLogRepository.save(msg);
                orphanCleaned++;
                log.warn("Orphan cleanup: marking msg id={} (correlationId={}) as FAILED — dispatched {}s ago with no SENT acknowledgement",
                        msg.getId(), msg.getSmppMessageId(),
                        java.time.Duration.between(msg.getDispatchedAt(), now).getSeconds());
            }
        }

        if (orphanCleaned > 0 || matrixCleaned > 0) {
            log.info("Cleanup: abandoned {} orphaned WebSocket dispatches, timed out {} Matrix.", 
                orphanCleaned, matrixCleaned);
        }
    }
}
