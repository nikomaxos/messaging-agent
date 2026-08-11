package com.messagingagent.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.kafka.SmsInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class InboundQueueConsumer {

    @Qualifier("smppCorrelationRedisTemplate")
    private final RedisTemplate<String, String> redis;
    
    private final ObjectMapper objectMapper;

    /**
     * Consumes raw inbound SMS as fast as possible from Kafka.
     * Pushes them into dynamic Redis ZSET queues.
     * This protects the application and PostgreSQL from 100k TPS bursts.
     */
    @KafkaListener(topics = "sms.inbound.raw", groupId = "messaging-agent-raw-queue")
    public void consumeRawInbound(SmsInboundEvent event) {
        try {
            // HLR lookup logic would go here to find actual Country and Network.
            // For now, default to ALL.
            String country = "ALL";
            String network = "ALL";
            String supplierId = "ALL"; // Will be determined dynamically via HLR/Routing table
            String systemId = event.getSystemId() != null ? event.getSystemId() : "UNKNOWN";

            String queueKey = "queue:" + systemId + ":" + country + ":" + network + ":" + supplierId;
            
            // Serialize event
            String jsonEvent = objectMapper.writeValueAsString(event);
            
            // Calculate Composite Score for Priority Queueing
            // Score = (priority * 10,000,000,000,000) + timestampMs
            // Priority 1 (OTP) scores ~11.7T. Priority 2 (Mktg) scores ~21.7T.
            // ZPOPMIN will always fetch OTP first!
            long baseTimestamp = event.getTimestampMs() != null ? event.getTimestampMs() : System.currentTimeMillis();
            long priority = event.getPriority() != null ? event.getPriority() : 2L;
            double compositeScore = (priority * 10_000_000_000_000L) + baseTimestamp;
            
            // Push to ZSET with composite score
            redis.opsForZSet().add(queueKey, jsonEvent, compositeScore);
            
            // Mark queue as active so the Dispatcher knows to poll it
            redis.opsForSet().add("smpp:queues:active", queueKey);
            
            log.debug("Queued raw message id={} into Redis {} with score={}", event.getCorrelationId(), queueKey, compositeScore);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SmsInboundEvent for queuing", e);
        }
    }
}
