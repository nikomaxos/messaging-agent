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
        monitorExecutor.scheduleAtFixedRate(this::monitorSessions, 5, 5, TimeUnit.SECONDS);
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
            connectAsync(supplier);
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
        config.setConnectTimeout(10000);
        config.setSystemId(supplier.getSystemId());
        config.setPassword(supplier.getPassword());
        
        if (supplier.getSystemType() != null && !supplier.getSystemType().isBlank()) {
            config.setSystemType(supplier.getSystemType());
        }

        config.getLoggingOptions().setLogBytes(false);
        config.getLoggingOptions().setLogPdu(true);

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
                    session.destroy();
                }
                log.warn("SMSC [{}] disconnected. Attempting reconnect...", supplier.getName());
                systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "Disconnected", "Attempting reconnect...");
                connectAsync(supplier);
            } else {
                // EnquireLink to keep alive based on EnquireLinkInterval
                try {
                    long interval = supplier.getEnquireLinkInterval() > 0 ? supplier.getEnquireLinkInterval() : 30000;
                    if (java.time.Duration.between(info.lastEnquireLink(), Instant.now()).toMillis() >= interval) {
                        info.setLastEnquireLink(Instant.now());
                        log.info("Sending EnquireLink to SMSC [{}]", supplier.getName());
                        EnquireLinkResp resp = session.enquireLink(new EnquireLink(), 5000);
                        log.info("SMSC [{}] responded to EnquireLink with status: {}", supplier.getName(), resp.getCommandStatus());
                    }
                } catch (SmppTimeoutException | SmppChannelException e) {
                    log.warn("EnquireLink failed for SMSC [{}]. Connection might be dead.", supplier.getName());
                    systemLogService.logAndBroadcast("WARN", "UPSTREAM: " + supplier.getName(), "EnquireLink Failed",
                        "Connection might be dead. Reason: " + e.getMessage());
                    session.destroy();
                    activeSessions.remove(supplier.getId());
                    disconnectedAt.putIfAbsent(supplier.getId(), Instant.now());
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
            log.error("Cannot route. SMSC Session not active for supplierId={}", finalSupplierId);
            return null;
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
                        messageLogRepository.findBySupplierMessageId(receiptedMessageId).ifPresent(l -> {
                            l.setFallbackDlrReceivedAt(Instant.now());
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
