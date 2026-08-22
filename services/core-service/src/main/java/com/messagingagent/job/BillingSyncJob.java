package com.messagingagent.job;

import com.messagingagent.model.ClientBilling;
import com.messagingagent.repository.ClientBillingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class BillingSyncJob {

    private final ClientBillingRepository clientBillingRepository;
    private final StringRedisTemplate redisTemplate;

    // Run every 30 seconds
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void syncBalancesFromRedis() {
        log.debug("Starting Redis -> DB balance sync...");
        
        List<ClientBilling> billings = clientBillingRepository.findAll();
        for (ClientBilling billing : billings) {
            String systemId = billing.getClient().getSystemId();
            String liveBalanceStr = redisTemplate.opsForValue().get("balance:" + systemId);
            if (liveBalanceStr != null) {
                try {
                    BigDecimal liveBalance = new BigDecimal(liveBalanceStr);
                    if (liveBalance.compareTo(billing.getBalance()) != 0) {
                        billing.setBalance(liveBalance);
                        clientBillingRepository.save(billing);
                        log.debug("Synced balance for client {}: {}", billing.getClientId(), liveBalance);
                    }
                } catch (NumberFormatException e) {
                    log.error("Invalid balance format in Redis for client {}: {}", systemId, liveBalanceStr);
                }
            } else {
                // If not in Redis, push DB balance to Redis to ensure it exists
                redisTemplate.opsForValue().set("balance:" + systemId, billing.getBalance().toString());
            }
        }
        
        log.debug("Finished balance sync.");
    }
}
