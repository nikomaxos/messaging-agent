package com.messagingagent.job;

import com.messagingagent.model.AccountBilling;
import com.messagingagent.repository.AccountBillingRepository;
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

    private final AccountBillingRepository accountBillingRepository;
    private final StringRedisTemplate redisTemplate;

    // Run every 30 seconds
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void syncBalancesFromRedis() {
        log.debug("Starting Redis -> DB balance sync...");
        
        List<AccountBilling> billings = accountBillingRepository.findAll();
        for (AccountBilling billing : billings) {
            String liveBalanceStr = redisTemplate.opsForValue().get("balance:acc:" + billing.getAccountId());
            if (liveBalanceStr != null) {
                try {
                    BigDecimal liveBalance = new BigDecimal(liveBalanceStr);
                    if (liveBalance.compareTo(billing.getBalance()) != 0) {
                        billing.setBalance(liveBalance);
                        accountBillingRepository.save(billing);
                        log.debug("Synced balance for account {}: {}", billing.getAccountId(), liveBalance);
                    }
                } catch (NumberFormatException e) {
                    log.error("Invalid balance format in Redis for account {}: {}", billing.getAccountId(), liveBalanceStr);
                }
            } else {
                // If not in Redis, push DB balance to Redis to ensure it exists
                redisTemplate.opsForValue().set("balance:acc:" + billing.getAccountId(), billing.getBalance().toString());
            }
        }
        
        log.debug("Finished balance sync.");
    }
}
