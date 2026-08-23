package com.messagingagent.service;

import com.messagingagent.model.SmppClient;
import com.messagingagent.model.RoutingRateLimit;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.RoutingRateLimitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RedisConfigSyncService {

    private final StringRedisTemplate redis;
    private final SmppClientRepository smppClientRepository;
    private final RoutingRateLimitRepository rateLimitRepository;

    public RedisConfigSyncService(
            StringRedisTemplate redis,
            SmppClientRepository smppClientRepository,
            RoutingRateLimitRepository rateLimitRepository) {
        this.redis = redis;
        this.smppClientRepository = smppClientRepository;
        this.rateLimitRepository = rateLimitRepository;
    }

    /**
     * Performs a full DB-to-Redis sync of all critical configuration when the application starts.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncAllConfigsOnStartup() {
        log.info("Starting initial synchronization of configuration to Redis...");
        
        List<SmppClient> clients = smppClientRepository.findAll();
        for (SmppClient client : clients) {
            syncClient(client);
        }
        log.info("Synced {} SMPP clients to Redis.", clients.size());

        List<RoutingRateLimit> rateLimits = rateLimitRepository.findAll();
        for (RoutingRateLimit rl : rateLimits) {
            syncRateLimit(rl);
        }
        log.info("Synced {} Routing Rate Limits to Redis.", rateLimits.size());
    }

    // ── SMPP Client Synchronization ────────────────────────────────────────────────────────

    public void syncClient(SmppClient client) {
        if (!client.isActive()) {
            deleteClient(client.getSystemId());
            return;
        }
        redis.opsForValue().set("config:client:" + client.getSystemId() + ":password", client.getPassword());
        redis.opsForValue().set("config:client:" + client.getSystemId() + ":priority", String.valueOf(client.getPriority()));
        
        if (client.getAccount() != null) {
            redis.opsForValue().set("client_to_account:" + client.getSystemId(), client.getAccount().getId().toString());
        }
        
        log.debug("Synced SMPP Client config to Redis: {}", client.getSystemId());
    }

    public void deleteClient(String systemId) {
        redis.delete("config:client:" + systemId + ":password");
        redis.delete("config:client:" + systemId + ":priority");
        redis.delete("client_to_account:" + systemId);
        log.debug("Deleted SMPP Client config from Redis: {}", systemId);
    }

    // ── Routing Rate Limit Synchronization ─────────────────────────────────────────────────

    public void syncRateLimit(RoutingRateLimit rl) {
        String key = buildRateLimitKey(rl.getCustomerProfileId(), rl.getCountryCode(), rl.getNetworkId(), rl.getSupplierId());
        redis.opsForValue().set(key, String.valueOf(rl.getSpeedTps()));
        log.debug("Synced Rate Limit config to Redis: {} -> {} TPS", key, rl.getSpeedTps());
    }

    public void deleteRateLimit(RoutingRateLimit rl) {
        String key = buildRateLimitKey(rl.getCustomerProfileId(), rl.getCountryCode(), rl.getNetworkId(), rl.getSupplierId());
        redis.delete(key);
        log.debug("Deleted Rate Limit config from Redis: {}", key);
    }

    private String buildRateLimitKey(String systemId, String country, String network, String supplierId) {
        return String.format("config:ratelimit:%s:%s:%s:%s",
                (systemId == null || systemId.isEmpty()) ? "ALL" : systemId,
                (country == null || country.isEmpty()) ? "ALL" : country,
                (network == null || network.isEmpty()) ? "ALL" : network,
                (supplierId == null || supplierId.isEmpty()) ? "ALL" : supplierId);
    }
}
