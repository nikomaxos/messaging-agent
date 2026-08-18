package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.messagingagent.smpp.model.SmscSupplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

@Service
@Slf4j
public class SmscConnectionManager {

    private final RestTemplate restTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    
    @Value("${core.service.url:http://ma-core-service:8080}")
    private String coreServiceUrl;
    
    private DefaultSmppClient smppClient;
    private final Map<Long, UpstreamSessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final Map<Long, Instant> disconnectedAt = new ConcurrentHashMap<>();
    private final java.util.Set<Long> connectingSuppliers = ConcurrentHashMap.newKeySet();
    private final Map<Long, SmscSupplier> supplierCache = new ConcurrentHashMap<>();
    
    public SmscConnectionManager(KafkaTemplate<String, String> kafkaTemplate, StringRedisTemplate redis) {
        this.restTemplate = new RestTemplate();
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.redis = redis;
    }
    
    public Instant getDisconnectedAt(Long supplierId) {
        return disconnectedAt.get(supplierId);
    }

    public static class UpstreamSessionInfo {
        private final SmppSession session;
        private final Instant boundAt;
        private Instant lastEnquireLink;
        
        public UpstreamSessionInfo(SmppSession session, Instant boundAt) {
            this.session = session;
            this.boundAt = boundAt;
            this.lastEnquireLink = Instant.now();
        }
        
        public SmppSession session() { return session; }
        public Instant boundAt() { return boundAt; }
        public Instant lastEnquireLink() { return lastEnquireLink; }
        public void setLastEnquireLink(Instant lastEnquireLink) { this.lastEnquireLink = lastEnquireLink; }
    }

    public UpstreamSessionInfo getSessionInfo(Long supplierId) {
        return activeSessions.get(supplierId);
    }
    
    private ScheduledExecutorService monitorExecutor;

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        startManager();
    }

    public synchronized void startManager() {
        if (monitorExecutor != null && !monitorExecutor.isShutdown()) {
            return;
        }
        
        smppClient = new DefaultSmppClient(Executors.newCachedThreadPool(), 1, null);
        monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        
        log.info("Starting SMSC Connection Manager...");
        loadAndConnectAll();
        
        monitorExecutor.scheduleAtFixedRate(this::monitorSessions, 2, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    public synchronized void stopManager() {
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        log.info("Stopping all SMSC upstream sessions...");
        activeSessions.values().forEach(info -> {
            try {
                info.session().unbind(5000);
                info.session().destroy();
            } catch (Exception e) {
                log.warn("Error stopping upstream session", e);
            }
        });
        activeSessions.clear();
        disconnectedAt.clear();
        connectingSuppliers.clear();
        
        if (smppClient != null) {
            smppClient.destroy();
        }
    }
    
    public synchronized void reload() {
        log.info("Reloading SMSC Connection Manager...");
        stopManager();
        startManager();
    }

    private void loadAndConnectAll() {
        try {
            com.fasterxml.jackson.databind.JsonNode rootNode = restTemplate.getForObject(coreServiceUrl + "/api/admin/smsc-suppliers", com.fasterxml.jackson.databind.JsonNode.class);
            if (rootNode != null && rootNode.isArray()) {
                log.info("Loaded {} SMSC suppliers from core-service", rootNode.size());
                for (com.fasterxml.jackson.databind.JsonNode node : rootNode) {
                    if (node.has("supplier")) {
                        SmscSupplier supplier = objectMapper.treeToValue(node.get("supplier"), SmscSupplier.class);
                        if (supplier != null && supplier.isActive()) {
                            supplierCache.put(supplier.getId(), supplier);
                            if (connectingSuppliers.add(supplier.getId())) {
                                connectAsync(supplier);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load SMSC suppliers from core-service: {}", e.getMessage(), e);
        }
    }

    private void connectAsync(SmscSupplier supplier) {
        CompletableFuture.runAsync(() -> {
            try {
                connectSynchronously(supplier);
            } catch (Exception e) {
                log.error("Failed initial connect for Supplier [{}] (id={}): {}", 
                        supplier.getName(), supplier.getId(), e.getMessage());
            } finally {
                connectingSuppliers.remove(supplier.getId());
            }
        });
    }

    private void connectSynchronously(SmscSupplier supplier) throws Exception {
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setWindowSize(1);
        config.setName("Supplier." + supplier.getId());
        
        SmppBindType type = SmppBindType.TRANSCEIVER;
        try {
            if (supplier.getBindType() != null) {
                type = SmppBindType.valueOf(supplier.getBindType().toUpperCase());
            }
        } catch (Exception ignored) { }
        
        config.setType(type);
        config.setHost(supplier.getHost());
        config.setPort(supplier.getPort());
        config.setConnectTimeout(5000);
        config.setSystemId(supplier.getSystemId());
        config.setPassword(supplier.getPassword());
        
        if (supplier.getSystemType() != null && !supplier.getSystemType().isBlank()) {
            config.setSystemType(supplier.getSystemType());
        }

        config.getLoggingOptions().setLogBytes(false);
        config.getLoggingOptions().setLogPdu(true);
        
        config.setWindowMonitorInterval(15000);
        config.setRequestExpiryTimeout(30000);
        config.setWindowWaitTimeout(60000);

        log.info("Connecting to SMSC [{}] at {}:{} as {}...", 
                supplier.getName(), supplier.getHost(), supplier.getPort(), type);

        SmppSession session = smppClient.bind(config, new UpstreamSessionHandler(supplier));
        activeSessions.put(supplier.getId(), new UpstreamSessionInfo(session, Instant.now()));
        disconnectedAt.remove(supplier.getId());
        
        log.info("Successfully bound to SMSC [{}] (id={})", supplier.getName(), supplier.getId());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 2000)
    private void monitorSessions() {
        // Periodically refresh cache from core-service and evict deleted/inactive suppliers
        try {
            com.fasterxml.jackson.databind.JsonNode rootNode = restTemplate.getForObject(coreServiceUrl + "/api/admin/smsc-suppliers", com.fasterxml.jackson.databind.JsonNode.class);
            if (rootNode != null && rootNode.isArray()) {
                java.util.Set<Long> validSupplierIds = new java.util.HashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode node : rootNode) {
                    if (node.has("supplier")) {
                        SmscSupplier s = objectMapper.treeToValue(node.get("supplier"), SmscSupplier.class);
                        if (s != null && s.isActive()) {
                            validSupplierIds.add(s.getId());
                            supplierCache.put(s.getId(), s);
                        }
                    }
                }
                
                // Evict any suppliers that were deleted or deactivated in DB (Ghost Connection Prevention)
                java.util.Set<Long> cachedIds = new java.util.HashSet<>(supplierCache.keySet());
                for (Long cachedId : cachedIds) {
                    if (!validSupplierIds.contains(cachedId)) {
                        log.info("SMSC Supplier id={} is no longer active in DB. Closing session and evicting...", cachedId);
                        supplierCache.remove(cachedId);
                        UpstreamSessionInfo info = activeSessions.remove(cachedId);
                        if (info != null && info.session() != null) {
                            try { info.session().unbind(3000); } catch (Exception ignored) {}
                            try { info.session().destroy(); } catch (Exception ignored) {}
                        }
                        disconnectedAt.remove(cachedId);
                        try {
                            redis.delete("smsc:supplier:status:" + cachedId);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to monitor sessions: {}", e.getMessage(), e);
        }
        
        for (SmscSupplier supplier : supplierCache.values()) {
            if (!supplier.isActive()) continue;
            
            UpstreamSessionInfo info = activeSessions.get(supplier.getId());
            SmppSession session = info != null ? info.session() : null;
            
            if (session == null || !session.isBound() || session.isClosed()) {
                if (session != null) {
                    activeSessions.remove(supplier.getId());
                    disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                    try { redis.delete("smsc:supplier:status:" + supplier.getId()); } catch(Exception ignored) {}
                    try { session.destroy(); } catch(Exception ignored) {}
                }
                
                if (connectingSuppliers.add(supplier.getId())) {
                    log.warn("SMSC [{}] disconnected. Attempting reconnect...", supplier.getName());
                    connectAsync(supplier);
                }
            } else {
                // Update Redis status for active bound session
                try {
                    redis.opsForValue().set("smsc:supplier:status:" + supplier.getId(), info.boundAt().toString(), java.time.Duration.ofSeconds(15));
                } catch (Exception ignored) {}
                Integer lifetimeMin = supplier.getMaxSessionLifetime();
                if (lifetimeMin != null && lifetimeMin > 0) {
                    long sessionAge = java.time.Duration.between(info.boundAt(), Instant.now()).toMillis();
                    long maxLifetimeMs = lifetimeMin * 60000L;
                    if (sessionAge >= maxLifetimeMs) {
                        log.warn("Session for SMSC [{}] reached max lifetime ({} minutes). Forcing rebind.", supplier.getName(), lifetimeMin);
                        CompletableFuture.runAsync(() -> {
                            try {
                                session.unbind(5000);
                                session.destroy();
                            } catch (Exception ignored) {}
                            activeSessions.remove(supplier.getId());
                            disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                        });
                        continue;
                    }
                }

                try {
                    long interval = supplier.getEnquireLinkInterval() > 0 ? supplier.getEnquireLinkInterval() : 15000;
                    if (java.time.Duration.between(info.lastEnquireLink(), Instant.now()).toMillis() >= interval) {
                        info.setLastEnquireLink(Instant.now());
                        
                        CompletableFuture.runAsync(() -> {
                            try {
                                EnquireLinkResp resp = session.enquireLink(new EnquireLink(), 10000);
                            } catch (SmppTimeoutException | SmppChannelException e) {
                                log.warn("EnquireLink failed for SMSC [{}]. Marking dead.", supplier.getName());
                                try { session.destroy(); } catch(Exception ignored) {}
                                activeSessions.remove(supplier.getId());
                                disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                            } catch (Exception e) {
                                try { session.destroy(); } catch(Exception ignored) {}
                                activeSessions.remove(supplier.getId());
                                disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }
    }
    
    public String submitMessage(Long supplierId, String source, String dest, String text) {
        UpstreamSessionInfo info = activeSessions.get(supplierId);
        if (info == null || info.session() == null || !info.session().isBound()) {
            for (int i = 0; i < 50; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                info = activeSessions.get(supplierId);
                if (info != null && info.session() != null && info.session().isBound()) {
                    break;
                }
            }
            if (info == null || info.session() == null || !info.session().isBound()) {
                log.error("Cannot route. SMSC Session not active for supplierId={}", supplierId);
                return null;
            }
        }
        
        SmppSession session = info.session();
        SmscSupplier supplier = supplierCache.get(supplierId);
        if (supplier == null) return null;

        try {
            LongSmsHelper.SmsPart[] parts = LongSmsHelper.createParts(text);
            String firstMessageId = null;

            for (LongSmsHelper.SmsPart part : parts) {
                SubmitSm sm = new SubmitSm();
                sm.setSourceAddress(new Address((byte) supplier.getSourceTon(), (byte) supplier.getSourceNpi(), source));
                sm.setDestAddress(new Address((byte) supplier.getDestTon(), (byte) supplier.getDestNpi(), dest));
                
                sm.setShortMessage(part.payload());
                sm.setDataCoding(part.dataCoding());

                if (part.hasUdh()) {
                    sm.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
                }
                sm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);

                log.info("Supplier bypassDuplicateFilter for {}: {}", supplier.getName(), supplier.isBypassDuplicateFilter());
                if (supplier.isBypassDuplicateFilter()) {
                    // Add random user_message_reference to safely bypass strict SMSC duplicate filters
                    short randRef = (short) (System.currentTimeMillis() % Short.MAX_VALUE);
                    sm.addOptionalParameter(new com.cloudhopper.smpp.tlv.Tlv(SmppConstants.TAG_USER_MESSAGE_REFERENCE, 
                            new byte[] { (byte)(randRef >>> 8), (byte)randRef }));
                }


                SubmitSmResp resp = session.submit(sm, 10000);
                if (resp.getCommandStatus() == SmppConstants.STATUS_OK) {
                    if (firstMessageId == null) {
                        firstMessageId = resp.getMessageId() != null ? resp.getMessageId() : "OK";
                    }
                } else {
                    log.error("Failed partial SUBMIT_SM. Part status: {}", resp.getCommandStatus());
                    return null;
                }
            }

            return firstMessageId;
        } catch (Exception e) {
            log.error("Failed to SUBMIT_SM to SMSC id={}: {}", supplierId, e.getMessage());
            return null;
        }
    }

    private class UpstreamSessionHandler extends DefaultSmppSessionHandler {
        private final SmscSupplier supplier;

        public UpstreamSessionHandler(SmscSupplier supplier) {
            super(log);
            this.supplier = supplier;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            if (pduRequest instanceof DeliverSm) {
                DeliverSm deliverSm = (DeliverSm) pduRequest;
                try {
                    String receiptedMessageId = null;
                    if (deliverSm.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID) != null) {
                        receiptedMessageId = new String(deliverSm.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID).getValue(), java.nio.charset.StandardCharsets.UTF_8);
                        receiptedMessageId = receiptedMessageId.replace("\0", "");
                    } else if (deliverSm.getShortMessage() != null) {
                        String msg = new String(deliverSm.getShortMessage(), java.nio.charset.StandardCharsets.UTF_8);
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)id:\\s*([^\\s]+)").matcher(msg);
                        if (m.find()) {
                            receiptedMessageId = m.group(1);
                        } else {
                            receiptedMessageId = msg.trim().split(" ")[0];
                        }
                    }

                    if (receiptedMessageId != null) {
                        String parsedStatus = "DELIVERED";
                        String parsedReason = "";
                        
                        // Parse status from message_state TLV if present (tag 0x0427)
                        com.cloudhopper.smpp.tlv.Tlv stateTlv = deliverSm.getOptionalParameter((short) 0x0427);
                        if (stateTlv != null && stateTlv.getValue() != null && stateTlv.getValue().length > 0) {
                            byte state = stateTlv.getValue()[0];
                            if (state == 2) parsedStatus = "DELIVERED"; // DELIVERED
                            else if (state == 5) parsedStatus = "FAILED"; // UNDELIVERABLE
                            else if (state != 1) parsedStatus = "FAILED"; // ENROUTE/etc treated as failed if not delivered, or maybe queued
                        }
                        
                        // Parse from text
                        if (deliverSm.getShortMessage() != null) {
                            String msg = new String(deliverSm.getShortMessage(), java.nio.charset.StandardCharsets.UTF_8);
                            java.util.regex.Matcher mStat = java.util.regex.Pattern.compile("(?i)stat:\\s*([^\\s]+)").matcher(msg);
                            if (mStat.find()) {
                                String statVal = mStat.group(1).toUpperCase();
                                if (statVal.contains("DELIV")) parsedStatus = "DELIVERED";
                                else if (statVal.contains("UNDEL") || statVal.contains("REJECT") || statVal.contains("FAIL")) parsedStatus = "FAILED";
                            }
                            java.util.regex.Matcher mErr = java.util.regex.Pattern.compile("(?i)err:\\s*([^\\s]+)").matcher(msg);
                            if (mErr.find()) {
                                parsedReason = mErr.group(1);
                            }
                        }

                        // Try network_error_code TLV (tag 0x0423)
                        com.cloudhopper.smpp.tlv.Tlv errTlv = deliverSm.getOptionalParameter((short) 0x0423);
                        if (errTlv != null && errTlv.getValue() != null && errTlv.getValue().length >= 3) {
                            int code = ((errTlv.getValue()[1] & 0xFF) << 8) | (errTlv.getValue()[2] & 0xFF);
                            if (parsedReason.isEmpty()) parsedReason = String.format("%03d", code);
                        }

                        log.info("DLR Received: id={}, status={}, reason={}", receiptedMessageId, parsedStatus, parsedReason);
                        
                        String dlrJson = String.format("{\"messageId\":\"%s\", \"status\":\"%s\", \"reason\":\"%s\", \"timestamp\":%d}", 
                                receiptedMessageId, parsedStatus, parsedReason, System.currentTimeMillis());
                        kafkaTemplate.send("sms.outbound.dlr", receiptedMessageId, dlrJson);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse DeliverSM for DLR", e);
                }
                
                com.cloudhopper.smpp.pdu.DeliverSmResp resp = (com.cloudhopper.smpp.pdu.DeliverSmResp) deliverSm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                return resp;
            } else if (pduRequest instanceof EnquireLink) {
                EnquireLinkResp resp = (EnquireLinkResp) pduRequest.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                return resp;
            } else if (pduRequest instanceof com.cloudhopper.smpp.pdu.Unbind) {
                com.cloudhopper.smpp.pdu.UnbindResp resp = (com.cloudhopper.smpp.pdu.UnbindResp) pduRequest.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                
                CompletableFuture.runAsync(() -> {
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                    UpstreamSessionInfo info = activeSessions.get(supplier.getId());
                    if (info != null && info.session() != null) {
                        try { info.session().destroy(); } catch(Exception ignored) {}
                    }
                    activeSessions.remove(supplier.getId());
                    disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                });
                return resp;
            }
            return pduRequest.createResponse();
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            log.warn("Upstream Channel unexpectedly closed for supplier [{}]", supplier.getName());
            activeSessions.remove(supplier.getId());
            disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
        }

        @Override
        public void fireUnknownThrowable(Throwable t) {
            log.error("Upstream Channel unknown throwable for supplier [{}]: ", supplier.getName(), t);
        }
    }
}
