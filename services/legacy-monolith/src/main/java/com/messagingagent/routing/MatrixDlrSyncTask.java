package com.messagingagent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.messagingagent.device.DeviceWebSocketService;
import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.model.Device;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.RoutingMode;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

@Service
@Slf4j
public class MatrixDlrSyncTask {

    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;
    private final MatrixRouteService matrixRouteService;
    private final DeviceWebSocketService deviceWebSocketService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${matrix.synapse.url:http://ma-synapse:8008}")
    private String synapseUrl;

    @Value("${matrix.bridge.db.url:jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/mautrix}")
    private String bridgeDbUrl;

    @Value("${matrix.bridge.db.username:${DB_USER:msgagent}}")
    private String bridgeDbUsername;

    @Value("${matrix.bridge.db.password:${DB_PASS:msgagent}}")
    private String bridgeDbPassword;

    private JdbcTemplate bridgeJdbc;

    // Cache of the latest sync tokens per device
    private final Map<Long, String> syncTokens = new ConcurrentHashMap<>();

    public MatrixDlrSyncTask(DeviceRepository deviceRepository,
                             MessageLogRepository messageLogRepository,
                             MatrixRouteService matrixRouteService,
                             DeviceWebSocketService deviceWebSocketService) {
        this.deviceRepository = deviceRepository;
        this.messageLogRepository = messageLogRepository;
        this.matrixRouteService = matrixRouteService;
        this.deviceWebSocketService = deviceWebSocketService;
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
        List<Device> onlineDevices = deviceRepository.findByStatus(Device.Status.ONLINE);

        for (Device device : onlineDevices) {
            try {
                String token = matrixRouteService.getRealToken(device);
                if (token == null) continue;

                String nextBatch = syncForDevice(device, token);
                if (nextBatch != null) {
                    syncTokens.put(device.getId(), nextBatch);
                }
            } catch (Exception e) {
                log.error("Failed to sync matrix DLRs for device {}: {}", device.getId(), e.getMessage());
            }
        }
    }

    private String syncForDevice(Device device, String token) {
        String sinceToken = syncTokens.get(device.getId());

        String url = synapseUrl + "/_matrix/client/v3/sync?timeout=0";
        if (sinceToken != null) {
            url += "&since=" + sinceToken;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        JsonNode response = restTemplate.exchange(url, HttpMethod.GET, request, JsonNode.class).getBody();

        if (response == null) return sinceToken;

        parseReceipts(response, device);

        JsonNode nextBatchNode = response.get("next_batch");
        return nextBatchNode != null ? nextBatchNode.asText() : sinceToken;
    }

    private void parseReceipts(JsonNode response, Device device) {
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
                                        log.info("Matrix sync: Event {} SEEN/READ by puppet {}", matrixEventId, readerMxid);
                                    }
                                }

                                if (genuineRead) {
                                    processDelivery(matrixEventId, device, true);
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
        // Phase 1: DISPATCHED → DELIVERED (check mss_delivery_sent)
        List<MessageLog> dispatchedMessages = messageLogRepository.findByStatusAndRoutingMode(
                MessageLog.Status.DISPATCHED, RoutingMode.MATRIX);

        for (MessageLog msg : dispatchedMessages) {
            checkBridgeStatus(msg);
        }

        // Phase 2: DELIVERED → SEEN/READ (check read_receipt_sent)
        List<MessageLog> deliveredMessages = messageLogRepository.findByStatusAndRoutingModeAndErrorDetailIsNull(
                MessageLog.Status.DELIVERED, RoutingMode.MATRIX);

        for (MessageLog msg : deliveredMessages) {
            checkBridgeStatus(msg);
        }
    }

    private void checkBridgeStatus(MessageLog msg) {
        String matrixEventId = msg.getSupplierMessageId();
        if (matrixEventId == null || matrixEventId.isBlank()) return;

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

                if (msg.getStatus() == MessageLog.Status.DISPATCHED && (isDelivered || isRead)) {
                    // DISPATCHED → DELIVERED (or SEEN if already read)
                    log.info("Bridge DB: {} (smppId={}) → {}", matrixEventId, msg.getSmppMessageId(),
                            isRead ? "SEEN/READ" : "DELIVERED");
                    if (msg.getDevice() != null) {
                        fireDeliveryEvent(msg, msg.getDevice(), isRead);
                    }
                } else if (msg.getStatus() == MessageLog.Status.DELIVERED && isRead) {
                    // DELIVERED → SEEN/READ upgrade
                    log.info("Bridge DB: {} (smppId={}) → SEEN/READ (upgrade)", matrixEventId, msg.getSmppMessageId());
                    if (msg.getDevice() != null) {
                        fireDeliveryEvent(msg, msg.getDevice(), true);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Bridge DB query failed for {}: {}", matrixEventId, e.getMessage());
        }
    }

    // ==================== Shared delivery processing ====================

    private void processDelivery(String matrixEventId, Device device, boolean isRead) {
        Optional<MessageLog> messageOpt = messageLogRepository.findBySupplierMessageId(matrixEventId);

        if (messageOpt.isPresent()) {
            MessageLog msg = messageOpt.get();

            // Only process if the message can be upgraded:
            // DISPATCHED → DELIVERED or SEEN/READ
            // DELIVERED → SEEN/READ (upgrade)
            if (msg.getStatus() == MessageLog.Status.DISPATCHED ||
                msg.getStatus() == MessageLog.Status.QUEUED ||
                (msg.getStatus() == MessageLog.Status.DELIVERED && isRead)) {

                Instant cutoffTime = msg.getDispatchedAt() != null ? msg.getDispatchedAt() : Instant.now();
                List<MessageLog> priorMessages = messageLogRepository.findPriorUnseenForMatrix(
                        device.getId(), msg.getDestinationAddress(), cutoffTime);

                log.info("Matrix DLR {} event_id={} (smppId={}). {} prior messages.",
                         isRead ? "SEEN/READ" : "DELIVERED",
                         matrixEventId, msg.getSmppMessageId(), priorMessages.size());

                boolean targetIncluded = false;

                for (MessageLog m : priorMessages) {
                    if (m.getId().equals(msg.getId())) {
                        targetIncluded = true;
                    }
                    fireDeliveryEvent(m, device, isRead);
                }

                if (!targetIncluded) {
                    fireDeliveryEvent(msg, device, isRead);
                }
            }
        }
    }

    private void fireDeliveryEvent(MessageLog msg, Device device, boolean isRead) {
        log.info("Matrix DLR: smppId={} → {}", msg.getSmppMessageId(), isRead ? "SEEN/READ" : "DELIVERED");
        SmsDeliveryResultEvent dlr = new SmsDeliveryResultEvent();
        dlr.setCorrelationId(msg.getSmppMessageId());
        dlr.setResult(SmsDeliveryResultEvent.Result.DELIVERED);
        if (isRead) {
            dlr.setErrorDetail("SEEN/READ");
        }

        deviceWebSocketService.handleDeliveryResult(dlr, device.getRegistrationToken());
    }
}
