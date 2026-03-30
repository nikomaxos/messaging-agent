package com.messagingagent.service;

import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.smpp.SmscConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Aggregates platform health from all infrastructure components.
 * Used by PlatformHealthController and AlertScheduler.
 */
@Service
@Slf4j
public class PlatformHealthService {

    private final DataSource dataSource;
    private final RedisTemplate<String, String> redis;
    private final SmscConnectionManager smscConnectionManager;
    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;
    private final com.messagingagent.repository.SmscSupplierRepository smscSupplierRepository;

    public PlatformHealthService(DataSource dataSource,
                                  @Qualifier("smppCorrelationRedisTemplate") RedisTemplate<String, String> redis,
                                  SmscConnectionManager smscConnectionManager,
                                  DeviceRepository deviceRepository,
                                  MessageLogRepository messageLogRepository,
                                  com.messagingagent.repository.SmscSupplierRepository smscSupplierRepository) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.smscConnectionManager = smscConnectionManager;
        this.deviceRepository = deviceRepository;
        this.messageLogRepository = messageLogRepository;
        this.smscSupplierRepository = smscSupplierRepository;
    }

    public record ComponentHealth(String name, boolean healthy, String detail) {}

    public Map<String, Object> getFullHealth() {
        Map<String, Object> result = new LinkedHashMap<>();

        // ── Infrastructure components ─────────────────────────────────
        List<ComponentHealth> components = new ArrayList<>();
        components.add(checkPostgres());
        components.add(checkRedis());
        components.add(checkKafka());
        result.put("components", components);

        // ── SMSC Suppliers ────────────────────────────────────────────
        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (var supplier : smscSupplierRepository.findAll()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", supplier.getId());
            s.put("name", supplier.getName());
            s.put("active", supplier.isActive());
            var info = smscConnectionManager.getSessionInfo(supplier.getId());
            boolean connected = info != null && info.session() != null && info.session().isBound();
            s.put("connected", connected);
            if (info != null && info.boundAt() != null) {
                s.put("uptimeSeconds", ChronoUnit.SECONDS.between(info.boundAt(), Instant.now()));
            }
            if (!connected) {
                Instant disconnectedAt = smscConnectionManager.getDisconnectedAt(supplier.getId());
                if (disconnectedAt != null) {
                    s.put("disconnectedSeconds", ChronoUnit.SECONDS.between(disconnectedAt, Instant.now()));
                } else {
                    s.put("disconnectedSeconds", 0L);
                }
            }
            suppliers.add(s);
        }
        result.put("smscSuppliers", suppliers);

        // ── Device fleet per group ────────────────────────────────────
        var allDevices = deviceRepository.findAll();
        Map<String, Map<String, Long>> fleetByGroup = new LinkedHashMap<>();
        for (var device : allDevices) {
            String groupName = device.getGroup() != null ? device.getGroup().getName() : "Ungrouped";
            fleetByGroup.computeIfAbsent(groupName, k -> new LinkedHashMap<>());
            var groupMap = fleetByGroup.get(groupName);
            String status = device.getStatus().name();
            groupMap.merge(status, 1L, Long::sum);
            groupMap.merge("total", 1L, Long::sum);
        }
        result.put("fleetByGroup", fleetByGroup);

        // ── Message pipeline stats (last 1h) ──────────────────────────
        Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        Map<String, Object> pipeline = new LinkedHashMap<>();
        try {
            pipeline.put("receivedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(
                    com.messagingagent.model.MessageLog.Status.RECEIVED, oneHourAgo));
            pipeline.put("deliveredLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(
                    com.messagingagent.model.MessageLog.Status.DELIVERED, oneHourAgo));
            pipeline.put("failedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(
                    com.messagingagent.model.MessageLog.Status.FAILED, oneHourAgo));
            pipeline.put("rcsFailedLastHour", messageLogRepository.countByStatusAndCreatedAtAfter(
                    com.messagingagent.model.MessageLog.Status.RCS_FAILED, oneHourAgo));
        } catch (Exception e) {
            pipeline.put("error", e.getMessage());
        }
        result.put("pipeline", pipeline);

        result.put("timestamp", Instant.now().toString());
        return result;
    }

    public ComponentHealth checkPostgres() {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(3);
            return new ComponentHealth("PostgreSQL", valid, valid ? "Connected" : "Connection invalid");
        } catch (Exception e) {
            return new ComponentHealth("PostgreSQL", false, e.getMessage());
        }
    }

    public ComponentHealth checkRedis() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return new ComponentHealth("Redis", "PONG".equalsIgnoreCase(pong), pong);
        } catch (Exception e) {
            return new ComponentHealth("Redis", false, e.getMessage());
        }
    }

    public ComponentHealth checkKafka() {
        // Kafka health is inferred from broker availability
        // Spring auto-config creates KafkaAdmin which tracks broker metadata
        try {
            org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.of("bootstrap.servers", System.getenv("KAFKA_BROKERS") != null ? System.getenv("KAFKA_BROKERS") : "localhost:9092",
                       "request.timeout.ms", "5000",
                       "default.api.timeout.ms", "5000")
            );
            var nodes = admin.describeCluster().nodes().get(5, java.util.concurrent.TimeUnit.SECONDS);
            admin.close(java.time.Duration.ofSeconds(2));
            return new ComponentHealth("Kafka", !nodes.isEmpty(), nodes.size() + " broker(s)");
        } catch (Exception e) {
            return new ComponentHealth("Kafka", false, e.getMessage());
        }
    }

    // ── Metric accessors for AlertScheduler ──────────────────────────
    public double getDeliveryRate(long windowMinutes) {
        Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
        long delivered = messageLogRepository.countUserMessagesByStatusAndCreatedAtAfter(
                com.messagingagent.model.MessageLog.Status.DELIVERED, since);
        long failed = messageLogRepository.countUserMessagesByStatusAndCreatedAtAfter(
                com.messagingagent.model.MessageLog.Status.FAILED, since);
        long rcsFailedCount = messageLogRepository.countUserMessagesByStatusAndCreatedAtAfter(
                com.messagingagent.model.MessageLog.Status.RCS_FAILED, since);
        long total = delivered + failed + rcsFailedCount;
        return total == 0 ? 100.0 : (delivered * 100.0 / total);
    }

    public long getQueuedCount() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        return messageLogRepository.countByStatusAndCreatedAtAfter(
                com.messagingagent.model.MessageLog.Status.RECEIVED, since);
    }

    public long getOfflineDeviceCount() {
        return deviceRepository.countByStatus(com.messagingagent.model.Device.Status.OFFLINE);
    }

    public long getOnlineDeviceCount() {
        return deviceRepository.countByStatus(com.messagingagent.model.Device.Status.ONLINE);
    }
}
