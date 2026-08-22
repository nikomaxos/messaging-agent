package com.messagingagent.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RatingEngine {

    @Qualifier("smppCorrelationRedisTemplate")
    private final RedisTemplate<String, String> redis;

    /**
     * Lua script to deduct balance atomically.
     * KEYS[1] = "balance:" + systemId
     * ARGV[1] = rate
     * Returns:
     *   "1" if successful (balance deducted or POSTPAID)
     *   "0" if insufficient funds
     *   "-1" if balance key not found
     */
    private static final String DEDUCT_SCRIPT =
            "local balanceStr = redis.call('GET', KEYS[1])\n" +
            "if not balanceStr then return '-1' end\n" +
            "local balance = tonumber(balanceStr)\n" +
            "local rate = tonumber(ARGV[1])\n" +
            "if balance >= rate then\n" +
            "  redis.call('INCRBYFLOAT', KEYS[1], -rate)\n" +
            "  return '1'\n" +
            "end\n" +
            "return '0'";

    private final DefaultRedisScript<String> deductRedisScript = new DefaultRedisScript<>(DEDUCT_SCRIPT, String.class);

    /**
     * Evaluates the cost of a message and deducts the balance if PREPAID.
     * @return true if allowed to send, false if insufficient funds.
     */
    public boolean evaluateAndDeduct(String systemId, String destinationNumber) {
        // 1. Get Billing Type
        String billingType = redis.opsForValue().get("client_type:" + systemId);
        if (billingType == null) {
            // Default to POSTPAID if not configured to prevent blocking traffic
            return true;
        }

        // 2. Get Tariff Plan ID
        String planIdStr = redis.opsForValue().get("client_plan:" + systemId);
        if (planIdStr == null) {
            return true; // No plan assigned
        }

        // 3. Find Rate for Destination (Simple prefix match for V1)
        // Extract prefix (e.g., +44, +1). For robust carrier billing this should match longest prefix.
        // As a simplification, we try +XX, then fallback to 'default' rate
        String rateStr = findRateForDestination(planIdStr, destinationNumber);
        if (rateStr == null) {
            // No rate found, allow for free or block? Let's allow for now.
            return true;
        }

        if ("POSTPAID".equalsIgnoreCase(billingType)) {
            // Just deduct (can go negative)
            redis.opsForValue().increment("balance:" + systemId, -Double.parseDouble(rateStr));
            return true;
        }

        // 4. Atomic PREPAID Deduction
        try {
            String result = redis.execute(deductRedisScript, Collections.singletonList("balance:" + systemId), rateStr);
            if ("0".equals(result)) {
                log.warn("System {} rejected due to insufficient funds. Required: {}", systemId, rateStr);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Error executing Redis Lua deduction script", e);
            return false; // Fail safe block? Actually fail safe allow to not disrupt service? Let's block.
        }
    }

    private String findRateForDestination(String planIdStr, String destination) {
        // Very basic longest prefix match simulation
        // Real implementation would use Redis Trie or iterate prefixes.
        // For V1, we check the first 2 digits, then first 1 digit, then wildcard '*'
        
        String cleanDest = destination.replaceAll("[^0-9]", "");
        
        if (cleanDest.length() >= 2) {
            String prefix2 = "+" + cleanDest.substring(0, 2);
            String rate = redis.opsForValue().get("tariff:" + planIdStr + ":" + prefix2);
            if (rate != null) return rate;
        }
        
        if (cleanDest.length() >= 1) {
            String prefix1 = "+" + cleanDest.substring(0, 1);
            String rate = redis.opsForValue().get("tariff:" + planIdStr + ":" + prefix1);
            if (rate != null) return rate;
        }

        return redis.opsForValue().get("tariff:" + planIdStr + ":*");
    }
}
