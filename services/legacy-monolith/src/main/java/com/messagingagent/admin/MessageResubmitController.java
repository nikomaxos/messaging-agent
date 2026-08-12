package com.messagingagent.admin;

import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Allows admin to mass-resubmit selected messages through a fallback SMPP SMSC.
 * Preserves the original customerMessageId / smppMessageId alignment.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Slf4j
public class MessageResubmitController {

    private final MessageLogRepository messageLogRepository;
    private final SmscSupplierRepository smscSupplierRepository;
    private final SmscConnectionManager smscConnectionManager;
    private final org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    public record ResubmitRequest(List<Long> messageIds, Long fallbackSmscId) {}
    public record ResubmitResult(Long originalId, Long newId, String status, String error) {}

    @PostMapping("/resubmit")
    public ResponseEntity<List<ResubmitResult>> resubmitMessages(@RequestBody ResubmitRequest request) {
        if (request.messageIds() == null || request.messageIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SmscSupplier fallbackSmsc = smscSupplierRepository.findById(request.fallbackSmscId()).orElse(null);
        if (fallbackSmsc == null) {
            return ResponseEntity.badRequest().body(List.of(
                new ResubmitResult(null, null, "ERROR", "Fallback SMSC not found: " + request.fallbackSmscId())
            ));
        }

        List<ResubmitResult> results = new ArrayList<>();

        for (Long msgId : request.messageIds()) {
            try {
                MessageLog original = messageLogRepository.findById(msgId).orElse(null);
                if (original == null) {
                    results.add(new ResubmitResult(msgId, null, "ERROR", "Message not found"));
                    continue;
                }

                // Create a child message log entry linked to the original
                MessageLog resent = MessageLog.builder()
                    .parentMessage(original)
                    .smppMessageId(original.getSmppMessageId())
                    .customerMessageId(original.getCustomerMessageId())
                    .sourceAddress(original.getSourceAddress())
                    .destinationAddress(original.getDestinationAddress())
                    .messageText(original.getMessageText())
                    .status(MessageLog.Status.QUEUED)
                    .deviceGroup(original.getDeviceGroup())
                    .fallbackSmsc(fallbackSmsc)
                    .fallbackStartedAt(Instant.now())
                    .createdAt(Instant.now())
                    .resendTrigger("MANUAL_RESUBMIT")
                    .build();

                messageLogRepository.save(resent);

                // Submit through the fallback SMSC via Kafka
                com.messagingagent.kafka.SmppOutboundEvent event = com.messagingagent.kafka.SmppOutboundEvent.builder()
                    .messageLogId(resent.getId())
                    .supplierId(fallbackSmsc.getId())
                    .sourceAddress(original.getSourceAddress())
                    .destinationAddress(original.getDestinationAddress())
                    .messageText(original.getMessageText())
                    .smppMessageId(original.getSmppMessageId())
                    .build();

                kafkaTemplate.send("smpp.outbound", event);

                log.info("Resubmitted message id={} via SMSC [{}], new id={}",
                    msgId, fallbackSmsc.getName(), resent.getId());

                results.add(new ResubmitResult(msgId, resent.getId(), "OK", null));
            } catch (Exception e) {
                log.error("Failed to resubmit message id={}", msgId, e);
                results.add(new ResubmitResult(msgId, null, "ERROR", e.getMessage()));
            }
        }

        return ResponseEntity.ok(results);
    }
}
