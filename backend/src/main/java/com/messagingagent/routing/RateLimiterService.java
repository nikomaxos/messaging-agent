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
@Slf4j
@RequiredArgsConstructor
public class RateLimiterService {

    @Qualifier("smppCorrelationRedisTemplate")
    private final RedisTemplate<String, String> redis;

    /**
     * Lua script for Minimum Delay Algorithm (Supports fractional speeds).
     * KEYS[1]: limit key (e.g. rate:limit:customer1:GR:Vodafone:Mautrix1)
     * ARGV[1]: mandatory delay in milliseconds
     * ARGV[2]: current timestamp in milliseconds
     *
     * Returns 1 if allowed, 0 if rejected.
     */
    private static final String MIN_DELAY_SCRIPT =
            "local key = KEYS[1] " +
            "local delay_ms = tonumber(ARGV[1]) " +
            "local now_ms = tonumber(ARGV[2]) " +
            "local last_dispatch = tonumber(redis.call('GET', key) or '0') " +
            "if now_ms - last_dispatch >= delay_ms then " +
            "    redis.call('SET', key, now_ms, 'PX', delay_ms * 2) " +
            "    return 1 " +
            "else " +
            "    return 0 " +
            "end";

    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(MIN_DELAY_SCRIPT, Long.class);

    /**
     * Attempts to consume 1 token for a specific queue using Minimum Delay.
     * @param customerId System ID
     * @param country Country Code (or ALL)
     * @param network Network ID (or ALL)
     * @param supplierId Supplier connection ID
     * @param speedTps Fractional Transactions Per Second (e.g. 0.1)
     * @return true if allowed, false if throttled
     */
    public boolean tryConsume(String customerId, String country, String network, String supplierId, BigDecimal speedTps) {
        if (speedTps == null || speedTps.compareTo(BigDecimal.ZERO) <= 0) return false;

        String key = "rate:limit:" + customerId + ":" + country + ":" + network + ":" + supplierId;
        
        // Calculate mandatory delay in milliseconds: 1000 / TPS
        // e.g. 0.1 TPS = 10000ms delay. 10 TPS = 100ms delay.
        long delayMs = BigDecimal.valueOf(1000)
                .divide(speedTps, 0, java.math.RoundingMode.HALF_UP)
                .longValue();
                
        long nowMs = System.currentTimeMillis();

        Long result = redis.execute(
                script,
                Collections.singletonList(key),
                String.valueOf(delayMs),
                String.valueOf(nowMs)
        );

        return result != null && result == 1L;
    }
}
