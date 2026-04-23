package com.messagingagent.kafka;

import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.smpp.SmppResponseService;
import com.messagingagent.smpp.SmscConnectionManager;
import com.messagingagent.smpp.SmscNotBoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmppOutboundConsumer {

    private final SmscConnectionManager smscConnectionManager;
    private final MessageLogRepository messageLogRepository;
    private final SmppResponseService smppResponseService;

    @KafkaListener(topics = "smpp.outbound", groupId = "messaging-agent-smpp-group")
    @Transactional
    public void consumeOutboundEvent(SmppOutboundEvent event) {
        log.info("Processing SMPP Outbound Event for messageLogId={} supplierId={}", event.getMessageLogId(), event.getSupplierId());

        String supplierMsgId = smscConnectionManager.submitMessage(
                event.getSupplierId(),
                event.getSourceAddress(),
                event.getDestinationAddress(),
                event.getMessageText()
        );

        if (supplierMsgId == null) {
            log.warn("Supplier {} is not bound. Throwing exception to queue/retry event.", event.getSupplierId());
            throw new SmscNotBoundException("SMSC not bound for supplierId=" + event.getSupplierId());
        }

        // Update DB
        MessageLog logEntry = messageLogRepository.findById(event.getMessageLogId()).orElse(null);
        if (logEntry != null) {
            logEntry.setStatus(MessageLog.Status.DELIVERED); // Mark delivered since offloaded
            logEntry.setFallbackMessageId(supplierMsgId);
            messageLogRepository.save(logEntry);
        }

        // Send DLR to original sender
        if (event.getSmppMessageId() != null) {
            smppResponseService.sendDeliverySm(event.getSmppMessageId());
        }
    }
}
