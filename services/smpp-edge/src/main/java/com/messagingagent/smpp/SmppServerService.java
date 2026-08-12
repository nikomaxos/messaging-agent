package com.messagingagent.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.messagingagent.kafka.SmsInboundEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * SMPP Server using CloudHopper.
 *
 * On each SUBMIT_SM:
 *  1. Assigns a UUID correlationId
 *  2. Stores in Redis (TTL = 300s):
 *       smpp:session:{correlationId}  → smppSessionId (String)
 *       smpp:source:{correlationId}   → sourceAddress
 *  3. Publishes SmsInboundEvent to Kafka "sms.inbound"
 */
@Service
@Slf4j
public class SmppServerService {

    private static final long CORRELATION_TTL_SECONDS = 300;

    private final KafkaTemplate<String, SmsInboundEvent> kafkaTemplate;
    private final SmppSessionRegistry sessionRegistry;
    private final RedisTemplate<String, String> redis;
    
    @Value("${app.smpp.host:0.0.0.0}")
    private String bindHost;
    
    @Value("${app.smpp.port:2775}")
    private int bindPort;

    private Instant uptimeStartedAt;

    public SmppServerService(KafkaTemplate<String, SmsInboundEvent> kafkaTemplate,
                              SmppSessionRegistry sessionRegistry,
                              @org.springframework.beans.factory.annotation.Qualifier("smppCorrelationRedisTemplate") RedisTemplate<String, String> redis) {
        this.kafkaTemplate = kafkaTemplate;
        this.sessionRegistry = sessionRegistry;
        this.redis = redis;
    }

    public Instant getUptimeStartedAt() {
        return uptimeStartedAt;
    }

    private DefaultSmppServer smppServer;

    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        int maxConn = 50;
        int timeout = 30000;

        SmppServerConfiguration config = new SmppServerConfiguration();
        config.setSystemId("Global_Net");
        config.setHost(bindHost);
        config.setPort(bindPort);
        config.setMaxConnectionSize(maxConn);
        config.setNonBlockingSocketsEnabled(true);
        config.setDefaultRequestExpiryTimeout(timeout);
        config.setDefaultWindowMonitorInterval(15_000);
        config.setDefaultWindowSize(8);
        config.setDefaultWindowWaitTimeout(timeout);
        config.setDefaultSessionCountersEnabled(true);
        config.setJmxEnabled(true);

        smppServer = new DefaultSmppServer(config, new SmppServerHandlerImpl(),
                Executors.newCachedThreadPool());
        try {
            smppServer.start();
            uptimeStartedAt = Instant.now();
            log.info("SMPP Server started on {}:{}", bindHost, bindPort);
        } catch (Exception e) {
            log.error("Failed to start SMPP Server", e);
            uptimeStartedAt = null;
        }
    }

    @PreDestroy
    public void stop() {
        if (smppServer != null) { 
            smppServer.destroy(); 
            uptimeStartedAt = null; 
            log.info("SMPP Server stopped and destroyed"); 
        }
    }

    public void restart() {
        stop();
        try {
            log.info("Waiting 3 seconds for SMPP port to fully unbind...");
            Thread.sleep(3000); // Give port time to unbind from TCP TIME_WAIT
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        start();
    }

    @Scheduled(fixedDelay = 10000)
    public void syncSessionsToRedis() {
        if (smppServer == null) return;
        
        // Group sessions by systemId
        java.util.Map<String, java.util.Map<String, String>> clientSessions = new java.util.HashMap<>();
        
        sessionRegistry.getAllSessions().forEach(info -> {
            String systemId = info.getSession().getConfiguration().getSystemId();
            String bindType = info.getSession().getConfiguration().getType().toString();
            long uptime = Duration.between(info.getBoundAt(), Instant.now()).getSeconds();
            
            clientSessions.computeIfAbsent(systemId, k -> new java.util.HashMap<>())
                          .put(info.getSessionId(), bindType + "|" + uptime);
        });
        
        clientSessions.forEach((systemId, sessions) -> {
            String key = "smpp:sessions:" + systemId;
            redis.opsForHash().putAll(key, sessions);
            redis.expire(key, Duration.ofSeconds(30));
        });
    }

    // ─── Redis key helpers ────────────────────────────────────────────────────

    public static String sessionKey(String correlationId)  { return "smpp:session:" + correlationId; }
    public static String sourceKey(String correlationId)   { return "smpp:source:"  + correlationId; }

    // ─── Inner handler ────────────────────────────────────────────────────────

    private class SmppServerHandlerImpl implements SmppServerHandler {

        @Override
        public void sessionBindRequested(Long sessionId,
                                          SmppSessionConfiguration cfg,
                                          BaseBind bindRequest) throws SmppProcessingException {
            String expectedPassword = redis.opsForValue().get("config:client:" + cfg.getSystemId() + ":password");
            if (expectedPassword == null || !expectedPassword.equals(cfg.getPassword())) {
                log.warn("SMPP Bind Rejected: Invalid credentials for systemId: {}", cfg.getSystemId());
                throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
            }
            log.info("SMPP bind accepted: systemId={}", cfg.getSystemId());
        }

        @Override
        public void sessionCreated(Long sessionId, SmppServerSession session,
                                    BaseBindResp preparedBindResponse) {
            String systemId = session.getConfiguration().getSystemId();
            Integer priority = 2; // Default Marketing
            String priorityStr = redis.opsForValue().get("config:client:" + systemId + ":priority");
            if (priorityStr != null) {
                try { priority = Integer.parseInt(priorityStr); } catch (Exception ignored) {}
            }
                                        
            session.serverReady(new MessageReceiverHandlerImpl(sessionId.toString(), systemId, priority, session));
            sessionRegistry.register(sessionId.toString(), new SmppSessionInfo(sessionId.toString(), session, Instant.now(), priority));
            log.info("SMPP session created id={} systemId={} priority={}", sessionId, systemId, priority);
        }

        @Override
        public void sessionDestroyed(Long sessionId, SmppServerSession session) {
            sessionRegistry.unregister(sessionId.toString());
            log.info("SMPP session destroyed id={}", sessionId);
        }
    }

    /**
     * Extends DefaultSmppSessionHandler so we only need to override
     * the methods we care about — the base class provides safe no-op
     * implementations for all optional callbacks (fireChannelUnexpectedlyClosed,
     * fireUnknownThrowable, fireExpectedPduResponseReceived, etc.)
     */
    private class MessageReceiverHandlerImpl extends DefaultSmppSessionHandler {

        private final String smppSessionId;
        private final String systemId;
        private final Integer priority;
        private final SmppServerSession session;

        MessageReceiverHandlerImpl(String smppSessionId, String systemId, Integer priority, SmppServerSession session) {
            super(log);
            this.smppSessionId = smppSessionId;
            this.systemId = systemId;
            this.priority = priority;
            this.session = session;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            log.info("RECEIVED PDU: commandId=0x{} name={}", Integer.toHexString(pduRequest.getCommandId()), pduRequest.getName());
            if (pduRequest instanceof SubmitSm sm) return handleSubmitSm(sm);
            return pduRequest.createResponse();
        }

        @Override
        public void fireUnknownThrowable(Throwable t) {
            log.error("SMPP Unknown Throwable: ", t);
        }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            log.warn("SMPP Channel unexpectedly closed");
        }

        private PduResponse handleSubmitSm(SubmitSm sm) {
            try {
                String srcAddr = sm.getSourceAddress() != null ? sm.getSourceAddress().getAddress() : "";
                String dstAddr = sm.getDestAddress()   != null ? sm.getDestAddress().getAddress()   : "";
                
                if (dstAddr == null || dstAddr.trim().isEmpty()) {
                    log.warn("Blocked SUBMIT_SM due to empty destination address");
                    SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                    resp.setCommandStatus(SmppConstants.STATUS_INVDSTADR);
                    return resp;
                }

                String aitBlockAction = redis.opsForValue().get("smpp:ait:block:" + dstAddr);
                if (aitBlockAction != null) {
                    log.warn("Blocked SUBMIT_SM for destination={} due to AIT auto-block policy (Action: {})", dstAddr, aitBlockAction);
                    SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                    
                    if ("FAKE_SUCCESS".equals(aitBlockAction) || "FAKE_DELIVERY".equals(aitBlockAction)) {
                        resp.setCommandStatus(SmppConstants.STATUS_OK);
                        String fakeMessageId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                        resp.setMessageId(fakeMessageId);
                        
                        if ("FAKE_DELIVERY".equals(aitBlockAction) && sm.getRegisteredDelivery() != 0) {
                            Thread.startVirtualThread(() -> {
                                try {
                                    Thread.sleep(1500); // simulate slight process delay
                                    com.cloudhopper.smpp.pdu.DeliverSm dlr = new com.cloudhopper.smpp.pdu.DeliverSm();
                                    dlr.setSourceAddress(sm.getDestAddress());
                                    dlr.setDestAddress(sm.getSourceAddress());
                                    dlr.setEsmClass((byte) 0x04); // Delivery Receipt
                                    dlr.setRegisteredDelivery((byte) 0x00);
                                    
                                    String text = "id:" + fakeMessageId + " sub:001 dlvrd:001 submit date:" + 
                                            java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmm").format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)) + 
                                            " done date:" + 
                                            java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmm").format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)) + 
                                            " stat:DELIVRD err:000 text:";
                                    dlr.setShortMessage(text.getBytes());
                                    session.sendRequestPdu(dlr, 5000, false);
                                } catch (Exception ignored) {}
                            });
                        }
                        return resp;
                    } else if ("REJECT_THROTTLED".equals(aitBlockAction)) {
                        resp.setCommandStatus(SmppConstants.STATUS_THROTTLED);
                        return resp;
                    } else {
                        resp.setCommandStatus(SmppConstants.STATUS_INVDSTADR); // default to invalid destination address
                        return resp;
                    }
                }

                byte[] shortMessage = sm.getShortMessage();
                if (shortMessage == null && sm.hasOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD)) {
                    shortMessage = sm.getOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD).getValue();
                }
                if (shortMessage == null) shortMessage = new byte[0];

                boolean hasUdh = (sm.getEsmClass() & SmppConstants.ESM_CLASS_UDHI_MASK) != 0;
                int refNum = 0, totalParts = 1, partNum = 1, udhLength = 0;

                if (hasUdh && shortMessage.length > 0) {
                    udhLength = shortMessage[0] & 0xFF;
                    if (shortMessage.length > udhLength) {
                        int pos = 1;
                        while (pos <= udhLength) {
                            int iei = shortMessage[pos] & 0xFF;
                            int ieLen = shortMessage[pos + 1] & 0xFF;
                            if (iei == 0x00 && ieLen == 3) {
                                refNum = shortMessage[pos + 2] & 0xFF;
                                totalParts = shortMessage[pos + 3] & 0xFF;
                                partNum = shortMessage[pos + 4] & 0xFF;
                                break;
                            } else if (iei == 0x08 && ieLen == 4) {
                                refNum = ((shortMessage[pos + 2] & 0xFF) << 8) | (shortMessage[pos + 3] & 0xFF);
                                totalParts = shortMessage[pos + 4] & 0xFF;
                                partNum = shortMessage[pos + 5] & 0xFF;
                                break;
                            }
                            pos += 2 + ieLen;
                        }
                    }
                }

                byte[] payloadBytes;
                if (hasUdh) {
                    payloadBytes = new byte[shortMessage.length - udhLength - 1];
                    System.arraycopy(shortMessage, udhLength + 1, payloadBytes, 0, payloadBytes.length);
                } else {
                    payloadBytes = shortMessage;
                }

                String partMessageId = UUID.randomUUID().toString();
                Duration ttl = Duration.ofSeconds(CORRELATION_TTL_SECONDS);
                redis.opsForValue().set(sessionKey(partMessageId), smppSessionId, ttl);
                redis.opsForValue().set(sourceKey(partMessageId),  srcAddr,       ttl);

                if (totalParts > 1) {
                    String concatKey = "smpp:concat:" + srcAddr + ":" + dstAddr + ":" + refNum;
                    String msgIdsKey = "smpp:concat:ids:" + srcAddr + ":" + dstAddr + ":" + refNum;

                    redis.opsForHash().put(concatKey, String.valueOf(partNum), java.util.Base64.getEncoder().encodeToString(payloadBytes));
                    redis.expire(concatKey, Duration.ofMinutes(10));
                    
                    redis.opsForList().rightPush(msgIdsKey, partMessageId);
                    redis.expire(msgIdsKey, Duration.ofMinutes(10));
                    
                    Long currentParts = redis.opsForValue().increment("smpp:concat:count:" + concatKey);
                    redis.expire("smpp:concat:count:" + concatKey, Duration.ofMinutes(10));

                    if (currentParts == null || currentParts < totalParts) {
                        log.info("Buffered multipart SMS: part {}/{} for refNum={}", partNum, totalParts, refNum);
                        SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                        resp.setCommandStatus(SmppConstants.STATUS_OK);
                        resp.setMessageId(partMessageId);
                        return resp;
                    }

                    log.info("All parts received for multipart SMS refNum={}. Recombining...", refNum);
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    for (int i = 1; i <= totalParts; i++) {
                        String partBase64 = (String) redis.opsForHash().get(concatKey, String.valueOf(i));
                        if (partBase64 != null) {
                            try { buffer.write(java.util.Base64.getDecoder().decode(partBase64)); } catch (Exception ignored) { }
                        }
                    }
                    payloadBytes = buffer.toByteArray();
                    
                    java.util.List<String> allMsgIds = redis.opsForList().range(msgIdsKey, 0, -1);
                    
                    redis.delete(concatKey);
                    redis.delete(msgIdsKey);
                    redis.delete("smpp:concat:count:" + concatKey);
                    
                    String mainCorrelationId = partMessageId;
                    if (allMsgIds != null && !allMsgIds.isEmpty()) {
                        mainCorrelationId = allMsgIds.get(0);
                        for (String id : allMsgIds) {
                            redis.opsForSet().add("smpp:linked:" + mainCorrelationId, id);
                        }
                        redis.expire("smpp:linked:" + mainCorrelationId, ttl);
                    }
                    
                    String msgText = CharsetUtil.decode(payloadBytes,
                            sm.getDataCoding() == 8 ? CharsetUtil.CHARSET_UCS_2 : CharsetUtil.CHARSET_GSM);

                    log.info("SUBMIT_SM (concat) from={} to={} correlationId={}", srcAddr, dstAddr, mainCorrelationId);

                    SmsInboundEvent event = SmsInboundEvent.builder()
                            .correlationId(mainCorrelationId)
                            .systemId(systemId)
                            .sourceAddress(srcAddr)
                            .destinationAddress(dstAddr)
                            .messageText(msgText)
                            .dataCoding(sm.getDataCoding())
                            .timestampMs(System.currentTimeMillis())
                            .priority(priority)
                            .build();

                    kafkaTemplate.send("sms.inbound.raw", dstAddr, event);

                    SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                    resp.setCommandStatus(SmppConstants.STATUS_OK);
                    resp.setMessageId(partMessageId);
                    return resp;
                } else {
                    String msgText = CharsetUtil.decode(payloadBytes,
                            sm.getDataCoding() == 8 ? CharsetUtil.CHARSET_UCS_2 : CharsetUtil.CHARSET_GSM);

                    log.info("SUBMIT_SM from={} to={} correlationId={}", srcAddr, dstAddr, partMessageId);

                    SmsInboundEvent event = SmsInboundEvent.builder()
                            .correlationId(partMessageId)
                            .systemId(systemId)
                            .sourceAddress(srcAddr)
                            .destinationAddress(dstAddr)
                            .messageText(msgText)
                            .dataCoding(sm.getDataCoding())
                            .timestampMs(System.currentTimeMillis())
                            .priority(priority)
                            .build();

                    kafkaTemplate.send("sms.inbound.raw", dstAddr, event);

                    SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                    resp.setCommandStatus(SmppConstants.STATUS_OK);
                    resp.setMessageId(partMessageId);
                    return resp;
                }

            } catch (Exception e) {
                log.error("Error processing SUBMIT_SM", e);
                SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_SYSERR);
                return resp;
            }
        }
    }
}
