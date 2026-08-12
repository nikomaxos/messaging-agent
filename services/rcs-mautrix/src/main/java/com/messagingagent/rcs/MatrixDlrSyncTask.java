package com.messagingagent.rcs;

import com.fasterxml.jackson.databind.JsonNode;
import com.messagingagent.kafka.SmsDeliveryResultEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MatrixDlrSyncTask {

    private final MatrixRouteService matrixRouteService;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${matrix.synapse.url:http://ma-synapse:8008}")
    private String synapseUrl;

    @Value("${matrix.bridge.db.url:jdbc:postgresql://ma-postgres:5432/mautrix}")
    private String bridgeDbUrl;

    @Value("${matrix.bridge.db.username:msgagent}")
    private String bridgeDbUsername;

    @Value("${matrix.bridge.db.password:msgagent}")
    private String bridgeDbPassword;

    private JdbcTemplate bridgeJdbc;

    // Cache of the latest sync tokens per device
    private final Map<Long, String> syncTokens = new ConcurrentHashMap<>();

    public MatrixDlrSyncTask(MatrixRouteService matrixRouteService,
                             StringRedisTemplate redisTemplate,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.matrixRouteService = matrixRouteService;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostConstruct
    public void initBridgeJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(bridgeDbUrl);
        ds.setUsername(bridgeDbUsername);
        ds.setPassword(bridgeDbPassword);
        this.bridgeJdbc = new JdbcTemplate(ds);
        log.info("MatrixDlrSyncTask: Bridge DB initialized at {}", bridgeDbUrl);
    }

    // ==================== MATRIX SYNC (Read/Seen receipts from Synapse) ====================

    @Scheduled(fixedDelayString = "${matrix.sync.delay-ms:2000}")
    public void synchronizeMatrixDlrs() {
        Set<String> deviceKeys = redisTemplate.keys("device:status:*");
        if (deviceKeys == null || deviceKeys.isEmpty()) return;

        for (String key : deviceKeys) {
            String status = redisTemplate.opsForValue().get(key);
            if (!"ONLINE".equals(status)) continue;

            try {
                Long deviceId = Long.parseLong(key.replace("device:status:", ""));
                String matrixId = redisTemplate.opsForValue().get("device:matrixId:" + deviceId);
                
                String token = matrixRouteService.getRealToken(deviceId, matrixId);
                if (token == null) continue;

                String nextBatch = syncForDevice(deviceId, token);
                if (nextBatch != null) {
                    syncTokens.put(deviceId, nextBatch);
                }
            } catch (Exception e) {
                log.error("Failed to sync matrix DLRs for device key {}: {}", key, e.getMessage());
            }
        }
    }

    private String syncForDevice(Long deviceId, String token) {
        String sinceToken = syncTokens.get(deviceId);

        String url = synapseUrl + "/_matrix/client/v3/sync?timeout=0";
        if (sinceToken != null) {
            url += "&since=" + sinceToken;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        JsonNode response = restTemplate.exchange(url, HttpMethod.GET, request, JsonNode.class).getBody();

        if (response == null) return sinceToken;

        parseReceipts(response, deviceId);

        JsonNode nextBatchNode = response.get("next_batch");
        return nextBatchNode != null ? nextBatchNode.asText() : sinceToken;
    }

    private void parseReceipts(JsonNode response, Long deviceId) {
        JsonNode rooms = response.path("rooms").path("join");
        Iterator<Map.Entry<String, JsonNode>> roomIterator = rooms.fields();

        while (roomIterator.hasNext()) {
            Map.Entry<String, JsonNode> roomEntry = roomIterator.next();
            JsonNode ephemeralEvents = roomEntry.getValue().path("ephemeral").path("events");

            if (ephemeralEvents.isArray()) {
                for (JsonNode event : ephemeralEvents) {
                    if ("m.receipt".equals(event.path("type").asText())) {
                        JsonNode content = event.path("content");

                        Iterator<Map.Entry<String, JsonNode>> eventIdIterator = content.fields();
                        while (eventIdIterator.hasNext()) {
                            Map.Entry<String, JsonNode> eventIdEntry = eventIdIterator.next();
                            String matrixEventId = eventIdEntry.getKey();
                            JsonNode receipts = eventIdEntry.getValue();

                            Iterator<String> receiptTypes = receipts.fieldNames();
                            while (receiptTypes.hasNext()) {
                                String receiptType = receiptTypes.next();
                                JsonNode typeNode = receipts.get(receiptType);

                                boolean genuineRead = false;

                                Iterator<String> readerKeys = typeNode.fieldNames();
                                while (readerKeys.hasNext()) {
                                    String readerMxid = readerKeys.next();
                                    if (readerMxid.startsWith("@device_")) {
                                        // Our own sender device — ignore
                                    } else if (readerMxid.startsWith("@gmessagesbot:")) {
                                        // Bridge bot — ignore (not genuine delivery)
                                    } else {
                                        // Puppet user = recipient SEEN/READ
                                        genuineRead = true;
                                    }
                                }

                                if (genuineRead) {
                                    processDelivery(matrixEventId, true);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== BRIDGE DB POLL (Delivery + Read status from mautrix-gmessages DB) ====================

    @Scheduled(fixedDelayString = "${matrix.bridge.dlr-poll-ms:3000}")
    public void pollBridgeDeliveryStatus() {
        Set<String> pendingKeys = redisTemplate.keys("pending_dlr:*");
        if (pendingKeys == null || pendingKeys.isEmpty()) return;

        for (String key : pendingKeys) {
            String matrixEventId = key.replace("pending_dlr:", "");
            String correlationId = redisTemplate.opsForValue().get(key);
            if (correlationId == null) continue;

            checkBridgeStatus(matrixEventId, correlationId, key);
        }
    }

    private void checkBridgeStatus(String matrixEventId, String correlationId, String redisKey) {
        try {
            List<Map<String, Object>> results = bridgeJdbc.queryForList(
                "SELECT metadata->>'mss_delivery_sent' as delivery_sent, " +
                "       metadata->>'read_receipt_sent' as read_sent " +
                "FROM message WHERE mxid = ? LIMIT 1",
                matrixEventId
            );

            if (!results.isEmpty()) {
                Map<String, Object> row = results.get(0);
                boolean isDelivered = "true".equals(row.get("delivery_sent"));
                boolean isRead = "true".equals(row.get("read_sent"));

                if (isRead) {
                    log.info("Bridge DB: {} (smppId={}) → SEEN/READ", matrixEventId, correlationId);
                    fireDeliveryEvent(correlationId, true);
                    redisTemplate.delete(redisKey);
                } else if (isDelivered) {
                    log.info("Bridge DB: {} (smppId={}) → DELIVERED", matrixEventId, correlationId);
                    fireDeliveryEvent(correlationId, false);
                    // Keep the key in Redis to wait for the SEEN/READ receipt upgrade
                }
            }
        } catch (Exception e) {
            log.debug("Bridge DB query failed for {}: {}", matrixEventId, e.getMessage());
        }
    }

    // ==================== Shared delivery processing ====================

    private void processDelivery(String matrixEventId, boolean isRead) {
        String correlationId = redisTemplate.opsForValue().get("pending_dlr:" + matrixEventId);
        if (correlationId != null) {
            fireDeliveryEvent(correlationId, isRead);
            if (isRead) {
                redisTemplate.delete("pending_dlr:" + matrixEventId);
            }
        }
    }

    private void fireDeliveryEvent(String correlationId, boolean isRead) {
        log.info("Matrix DLR: smppId={} → {}", correlationId, isRead ? "SEEN/READ" : "DELIVERED");
        SmsDeliveryResultEvent dlr = new SmsDeliveryResultEvent();
        dlr.setCorrelationId(correlationId);
        dlr.setResult(SmsDeliveryResultEvent.Result.DELIVERED);
        if (isRead) {
            dlr.setErrorDetail("SEEN/READ");
        }

        kafkaTemplate.send("sms.delivery.result", dlr);
    }
}
