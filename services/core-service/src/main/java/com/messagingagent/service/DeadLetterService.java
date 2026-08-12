package com.messagingagent.service;

import com.messagingagent.model.DeadLetterMessage;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.DeadLetterRepository;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Moves permanently failed messages to the dead-letter queue.
 * Runs every 5 minutes and looks for messages in FAILED status
 * that have been stuck for > 10 minutes (indicating exhausted retries).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final MessageLogRepository messageLogRepository;
    private final DeadLetterRepository deadLetterRepository;

    @Scheduled(fixedRate = 300_000) // every 5 min
    @Transactional
    public void moveFailedToDlq() {
        Instant cutoff = Instant.now().minusSeconds(600); // failed > 10min ago
        List<MessageLog> failed = messageLogRepository
                .findByStatusAndCreatedAtBefore(MessageLog.Status.FAILED, cutoff);

        if (failed.isEmpty()) return;

        int moved = 0;
        for (MessageLog msg : failed) {
            // Skip if already in DLQ
            if (deadLetterRepository.findById(msg.getId()).isPresent()) continue;

            deadLetterRepository.save(DeadLetterMessage.builder()
                    .originalMessageId(msg.getId())
                    .sourceAddress(msg.getSourceAddress())
                    .destinationAddress(msg.getDestinationAddress())
                    .messageText(msg.getMessageText())
                    .failureReason(msg.getErrorDetail())
                    .retryCount(0)
                    .build());
            moved++;
        }

        if (moved > 0) {
            log.info("DLQ: moved {} failed messages to dead-letter queue", moved);
        }
    }

    public Page<DeadLetterMessage> getDeadLetters(int page, int size) {
        return deadLetterRepository.findByStatusOrderByCreatedAtDesc(
                DeadLetterMessage.DlqStatus.DEAD, PageRequest.of(page, size));
    }

    @Transactional
    public DeadLetterMessage retry(Long id) {
        DeadLetterMessage dlq = deadLetterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("DLQ entry not found: " + id));

        // Re-queue the original message
        MessageLog original = messageLogRepository.findById(dlq.getOriginalMessageId()).orElse(null);
        if (original != null) {
            original.setStatus(MessageLog.Status.QUEUED);
            original.setErrorDetail(null);
            messageLogRepository.save(original);
        }

        dlq.setStatus(DeadLetterMessage.DlqStatus.RETRIED);
        dlq.setRetriedAt(Instant.now());
        dlq.setRetryCount(dlq.getRetryCount() + 1);
        return deadLetterRepository.save(dlq);
    }

    @Transactional
    public DeadLetterMessage discard(Long id) {
        DeadLetterMessage dlq = deadLetterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("DLQ entry not found: " + id));
        dlq.setStatus(DeadLetterMessage.DlqStatus.DISCARDED);
        dlq.setDiscardedAt(Instant.now());
        return deadLetterRepository.save(dlq);
    }

    public long countDead() {
        return deadLetterRepository.countByStatus(DeadLetterMessage.DlqStatus.DEAD);
    }
}
