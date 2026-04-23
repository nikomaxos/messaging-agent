package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import java.time.Instant;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.DeliverSmResp;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.EnquireLinkResp;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.SmscSupplierRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.messagingagent.service.SystemLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmscConnectionManager {

    private final SmscSupplierRepository supplierRepository;
    private final com.messagingagent.repository.MessageLogRepository messageLogRepository;
    private final SystemLogService systemLogService;
    
    private DefaultSmppClient smppClient;
    private final Map<Long, UpstreamSessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final Map<Long, Instant> disconnectedAt = new ConcurrentHashMap<>();
    private final java.util.Set<Long> connectingSuppliers = ConcurrentHashMap.newKeySet();
    
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
    
    // Scheduled executor to handle EnquireLinks and reconnects
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
        
        // Setup a monitor task to keep links alive and reconnect disconnected ones
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
        var suppliers = supplierRepository.findByActiveTrue();
        log.info("Found {} active SMSC suppliers to connect.", suppliers.size());
        
        for (SmscSupplier supplier : suppliers) {
            if (connectingSuppliers.add(supplier.getId())) {
                connectAsync(supplier);
            }
        }
    }

    private void connectAsync(SmscSupplier supplier) {
        CompletableFuture.runAsync(() -> {
            try {
                connectSynchronously(supplier);
            } catch (Exception e) {
                log.error("Failed initial connect for Supplier [{}] (id={}): {}", 
                        supplier.getName(), supplier.getId(), e.getMessage());
                systemLogService.logAndBroadcast("ERROR", "UPSTREAM: " + supplier.getName(), "Bind Failed", e.getMessage());
            } finally {
                connectingSuppliers.remove(supplier.getId());
            }
        });
    }

    private void connectSynchronously(SmscSupplier supplier) throws Exception {
        SmppSessionConfiguration config = new SmppSessionConfiguration();
        config.setWindowSize(1);
        config.setName("Supplier." + supplier.getId());
        
        // Enum map
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
        
        long enquireLinkInterval = supplier.getEnquireLinkInterval() > 0 ? supplier.getEnquireLinkInterval() : 50000;

        // Precaution measures for ghost connections:
        config.setWindowMonitorInterval(2000);
        config.setRequestExpiryTimeout(enquireLinkInterval);
        config.setWindowWaitTimeout(enquireLinkInterval);

        log.info("Connecting to SMSC [{}] at {}:{} as {}...", 
                supplier.getName(), supplier.getHost(), supplier.getPort(), type);
        systemLogService.logAndBroadcast("INFO", "UPSTREAM: " + supplier.getName(), "Binding", 
            "Connecting to " + supplier.getHost() + ":" + supplier.getPort());

        SmppSession session = smppClient.bind(config, new UpstreamSessionHandler(supplier));
        activeSessions.put(supplier.getId(), new UpstreamSessionInfo(session, Instant.now()));
        disconnectedAt.remove(supplier.getId());
        
        log.info("Successfully bound to SMSC [{}] (id={})", supplier.getName(), supplier.getId());
        systemLogService.logAndBroadcast("INFO", "UPSTREAM: " + supplier.getName(), "Bound", 
            "Successfully bound to SMSC as " + type);
    }

    private void monitorSessions() {
        var suppliers = supplierRepository.findByActiveTrue();
        
        for (SmscSupplier supplier : suppliers) {
            UpstreamSessionInfo info = activeSessions.get(supplier.getId());
            SmppSession session = info != null ? info.session() : null;
            
            // Reconnect if dead or not existing
            if (session == null || !session.isBound() || session.isClosed()) {
                if (session != null) {
                    activeSessions.remove(supplier.getId());
                    disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                    try { session.destroy(); } catch(Exception ignored) {}
                }
                
                if (connectingSuppliers.add(supplier.getId())) {
                    log.warn("SMSC [{}] disconnected. Attempting reconnect...", supplier.getName());
                    systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "Disconnected", "Attempting reconnect...");
                    connectAsync(supplier);
                }
            } else {
                // Rebind periodically to force authentication check against proxy
                Integer lifetimeMin = supplier.getMaxSessionLifetime();
                if (lifetimeMin != null && lifetimeMin > 0) {
                    long sessionAge = java.time.Duration.between(info.boundAt(), Instant.now()).toMillis();
                    long maxLifetimeMs = lifetimeMin * 60000L;
                    if (sessionAge >= maxLifetimeMs) {
                        log.warn("Session for SMSC [{}] reached max lifetime ({} minutes). Forcing rebind to verify authentication.", supplier.getName(), lifetimeMin);
                        systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "Forced Rebind",
                            "Session reached max lifetime. Reconnecting to verify authentication.");
                        
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

                // EnquireLink to keep alive based on EnquireLinkInterval
                try {
                    long interval = supplier.getEnquireLinkInterval() > 0 ? supplier.getEnquireLinkInterval() : 50000;
                    if (java.time.Duration.between(info.lastEnquireLink(), Instant.now()).toMillis() >= interval) {
                        info.setLastEnquireLink(Instant.now());
                        
                        CompletableFuture.runAsync(() -> {
                            try {
                                log.info("Sending EnquireLink to SMSC [{}] with timeout {}ms", supplier.getName(), interval);
                                EnquireLinkResp resp = session.enquireLink(new EnquireLink(), interval);
                                log.info("SMSC [{}] responded to EnquireLink with status: {}", supplier.getName(), resp.getCommandStatus());
                            } catch (SmppTimeoutException | SmppChannelException e) {
                                log.warn("EnquireLink failed for SMSC [{}]. Connection might be dead.", supplier.getName());
                                systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "EnquireLink Failed",
                                    "Connection dead. Reason: " + e.getMessage());
                                try { session.destroy(); } catch(Exception ignored) {}
                                activeSessions.remove(supplier.getId());
                                disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
                            } catch (Exception e) {
                                log.error("Unknown error during EnquireLink for SMSC [{}]: {}", supplier.getName(), e.getMessage());
                                systemLogService.logAndBroadcast("ERROR", "UPSTREAM: " + supplier.getName(), "EnquireLink Error",
                                    "Unexpected error, marking dead. Reason: " + e.getMessage());
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
    
    /**
     * Sends an SMS via the specified SMSC supplier using standard CloudHopper SubmitSm.
     */
    public String submitMessage(Long supplierId, String source, String dest, String text) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalSupplierId = supplierId; // Fix lint warning
        UpstreamSessionInfo info = activeSessions.get(finalSupplierId);
        if (info == null || info.session() == null || !info.session().isBound()) {
            // Queue mechanism: wait up to 5 seconds for the connection to re-bind (e.g. during max lifetime reconnects)
            for (int i = 0; i < 50; i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                info = activeSessions.get(finalSupplierId);
                if (info != null && info.session() != null && info.session().isBound()) {
                    break;
                }
            }
            if (info == null || info.session() == null || !info.session().isBound()) {
                log.error("Cannot route. SMSC Session not active for supplierId={} after waiting", finalSupplierId);
                return null;
            }
        }
        
        SmppSession session = info.session();
        SmscSupplier supplier = supplierRepository.findById(finalSupplierId).orElse(null);
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

            // Increment sent counter natively
            supplier.setSentCount(supplier.getSentCount() + 1);
            supplierRepository.save(supplier);
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
            log.info("Received upstream PDU from {}: name={} status={}", 
                supplier.getName(), pduRequest.getName(), pduRequest.getCommandStatus());
                
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
                            // some providers use plain text for the ID if no other text is present
                            receiptedMessageId = msg.trim().split(" ")[0];
                        }
                    }

                    if (receiptedMessageId != null) {
                        log.info("Parsed fallback receipted message id: {}", receiptedMessageId);
                        
                        final String finalReceiptedMessageId = receiptedMessageId;
                        // First try fallbackMessageId (new standard), then supplierMessageId (legacy)
                        messageLogRepository.findByFallbackMessageId(finalReceiptedMessageId)
                            .or(() -> messageLogRepository.findBySupplierMessageId(finalReceiptedMessageId))
                            .ifPresent(l -> {
                                l.setFallbackDlrReceivedAt(Instant.now());
                                // Only mark as DELIVERED if it's currently FAILED or DISPATCHED or RCS_FAILED, 
                                // to track ultimate fallback success.
                                if (l.getStatus() != MessageLog.Status.DELIVERED) {
                                    l.setStatus(MessageLog.Status.DELIVERED);
                                }
                                messageLogRepository.save(l);
                        });
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
                log.warn("Received Unbind request from SMSC [{}]. The server is actively dropping the connection.", supplier.getName());
                systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "Unbind Received",
                    "The SMSC requested to unbind the connection.");
                    
                com.cloudhopper.smpp.pdu.UnbindResp resp = (com.cloudhopper.smpp.pdu.UnbindResp) pduRequest.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                
                // Asynchronously clean up our session to allow the response to go out first
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
            systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "Channel Closed",
                "Upstream Channel unexpectedly closed");
            activeSessions.remove(supplier.getId());
            disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
        }

        @Override
        public void fireUnknownThrowable(Throwable t) {
            log.error("Upstream Channel unknown throwable for supplier [{}]: ", supplier.getName(), t);
            systemLogService.logAndBroadcast("ERROR", "UPSTREAM: " + supplier.getName(), "Channel Error",
                "Throwable: " + t.getMessage());
        }
    }

    
    public void bindSupplier(SmscSupplier supplier) {
        log.info("Manual bind execution for SMSC [{}]", supplier.getName());
        connectAsync(supplier);
    }
    
    public void unbindSupplier(Long supplierId) {
        UpstreamSessionInfo info = activeSessions.remove(supplierId);
        disconnectedAt.put(supplierId, Instant.now());
        if (info != null && info.session() != null) {
            log.info("Manual unbind execution for SMSC supplierId={}", supplierId);
            try {
                info.session().unbind(5000);
                info.session().destroy();
            } catch (Exception e) {
                log.warn("Error correctly unbinding session for supplier {}", supplierId, e);
            }
        }
    }
}
