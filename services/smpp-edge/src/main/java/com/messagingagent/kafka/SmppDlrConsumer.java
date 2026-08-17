package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.smpp.SmppResponseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmppDlrConsumer {

    private final SmppResponseService smppResponseService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "sms.delivery.receipt", groupId = "smpp-edge-dlr-group")
    public void consumeDeliveryReceipt(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String correlationId = (String) event.get("correlationId");
            String status = (String) event.get("status");
            String reason = (String) event.get("reason");

            if (correlationId == null || status == null) {
                log.warn("Invalid DLR event: {}", messageJson);
                return;
            }

            log.info("Received DLR for correlationId={}, status={}, reason={}", correlationId, status, reason);

            if ("DELIVERED".equalsIgnoreCase(status)) {
                smppResponseService.sendDeliverySm(correlationId);
            } else {
                smppResponseService.sendDeliveryFailure(correlationId, reason);
            }

        } catch (Exception e) {
            log.error("Failed to process sms.delivery.receipt message", e);
        }
    }
}
