package com.messagingagent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.kafka.SmsInboundEvent;
import com.messagingagent.model.RoutingRateLimit;
import com.messagingagent.repository.RoutingRateLimitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Profile("worker")
@RequiredArgsConstructor
@Slf4j
public class RateLimitDispatcher {

    @Qualifier("smppCorrelationRedisTemplate")
    private final RedisTemplate<String, String> redis;
    
    private final RateLimiterService rateLimiterService;
    private final RoutingRateLimitRepository rateLimitRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    // Use Virtual Threads for lightweight concurrent dispatching
    private final ExecutorService dispatcherExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Runs continuously every 10ms. 
     * Iterates all active queues and dispatches messages if tokens are available.
     */
    @Scheduled(fixedDelay = 10)
    public void dispatchQueues() {
        Set<String> activeQueues = redis.opsForSet().members("smpp:queues:active");
        if (activeQueues == null || activeQueues.isEmpty()) {
            return;
        }

        for (String queueKey : activeQueues) {
            dispatcherExecutor.submit(() -> processQueue(queueKey));
        }
    }

    private void processQueue(String queueKey) {
        try {
            // Check if queue has messages
            Long size = redis.opsForZSet().zCard(queueKey);
            if (size == null || size == 0) {
                redis.opsForSet().remove("smpp:queues:active", queueKey);
                return;
            }

            // Extract routing parameters from queueKey: queue:{systemId}:{country}:{network}:{supplierId}
            String[] parts = queueKey.split(":");
            if (parts.length < 5) return;
            
            String systemId = parts[1];
            String country = parts[2];
            String network = parts[3];
            String supplierId = parts[4];

            // Get TPS limit
            BigDecimal tps = getTpsLimit(systemId, country, network, supplierId);

            // Check token bucket via Minimum Delay algorithm
            if (rateLimiterService.tryConsume(systemId, country, network, supplierId, tps)) {
                
                // Pop the oldest message (lowest timestamp score)
                Set<String> popped = redis.opsForZSet().range(queueKey, 0, 0);
                if (popped != null && !popped.isEmpty()) {
                    String jsonEvent = popped.iterator().next();
                    
                    // Remove it from the queue
                    redis.opsForZSet().remove(queueKey, jsonEvent);
                    
                    // Route it to the main processing topic
                    SmsInboundEvent event = objectMapper.readValue(jsonEvent, SmsInboundEvent.class);
                    kafkaTemplate.send("sms.inbound", event.getDestinationAddress(), event);
                    
                    log.debug("Dispatched message id={} at TPS={}", event.getCorrelationId(), tps);
                }
            }
        } catch (Exception e) {
            log.error("Error processing queue {}", queueKey, e);
        }
    }

    private BigDecimal getTpsLimit(String systemId, String country, String network, String supplierId) {
        RoutingRateLimit config = rateLimitRepository
                .findByCustomerProfileIdAndCountryCodeAndNetworkIdAndSupplierId(systemId, country, network, supplierId)
                .orElse(null);
                
        if (config == null && (!"ALL".equals(country) || !"ALL".equals(network) || !"ALL".equals(supplierId))) {
            config = rateLimitRepository
                .findByCustomerProfileIdAndCountryCodeAndNetworkIdAndSupplierId(systemId, "ALL", "ALL", "ALL")
                .orElse(null);
        }
        
        return config != null ? config.getSpeedTps() : BigDecimal.TEN; // Default 10 TPS
    }
}
