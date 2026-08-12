package com.messagingagent.rcs;

import com.messagingagent.kafka.RcsOutboundEvent;
import com.messagingagent.kafka.SmsDeliveryResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class RcsOutboundConsumer {

    private final MatrixRouteService matrixRouteService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "outbound.rcs", groupId = "rcs-mautrix-group")
    public void consumeRcsOutbound(RcsOutboundEvent event) {
        log.info("Received outbound RCS for correlationId={}", event.getSmppMessageId());

        try {
            // Send the message using Mautrix Synapse API
            String matrixEventId = matrixRouteService.sendMessage(
                event.getDeviceId(),
                event.getMatrixId(),
                event.getDestinationAddress(),
                event.getMessageText()
            );

            if (matrixEventId == null) {
                log.error("Matrix dispatch failed for correlationId={}", event.getSmppMessageId());
                publishError(event.getSmppMessageId(), "Matrix Gateway Error");
                return;
            }

            log.info("Successfully dispatched to Matrix. correlationId={}, matrixEventId={}", 
                     event.getSmppMessageId(), matrixEventId);

            // Store the pending DLR in Redis so MatrixDlrSyncTask can poll it
            redisTemplate.opsForValue().set(
                "pending_dlr:" + matrixEventId, 
                event.getSmppMessageId(), 
                Duration.ofDays(3)
            );

            // Publish a SENT result. The DLR poll task will handle final DELIVERED/READ
            SmsDeliveryResultEvent dlr = new SmsDeliveryResultEvent();
            dlr.setCorrelationId(event.getSmppMessageId());
            dlr.setResult(SmsDeliveryResultEvent.Result.SENT);
            dlr.setErrorDetail(matrixEventId); // Pass matrix event ID in the detail so Routing Engine can save it
            
            kafkaTemplate.send("sms.delivery.result", dlr);

        } catch (Exception e) {
            log.error("Fatal error dispatching RCS for correlationId={}: {}", event.getSmppMessageId(), e.getMessage());
            publishError(event.getSmppMessageId(), e.getMessage());
        }
    }

    private void publishError(String correlationId, String error) {
        SmsDeliveryResultEvent dlr = new SmsDeliveryResultEvent();
        dlr.setCorrelationId(correlationId);
        dlr.setResult(SmsDeliveryResultEvent.Result.ERROR);
        dlr.setErrorDetail(error);
        kafkaTemplate.send("sms.delivery.result", dlr);
    }
}
