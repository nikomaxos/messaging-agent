package com.messagingagent.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppServerHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.messagingagent.kafka.SmsInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * SMPP Server using CloudHopper.
 *
 * On each SUBMIT_SM:
 *  1. Assigns a UUID correlationId
 *  2. Stores in Redis:
 *       smpp:session:{correlationId}  → smppSessionId (String)
 *       smpp:source:{correlationId}   → sourceAddress
 *  3. Publishes SmsInboundEvent to Kafka sms.inbound
 *
 * Redis entries expire after CORRELATION_TTL_SECONDS (default 300s) to
 * prevent memory leaks for undelivered messages.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmppServerService {

    private static final long CORRELATION_TTL_SECONDS = 300;

    private final KafkaTemplate<String, SmsInboundEvent> kafkaTemplate;
    private final SmppSessionRegistry sessionRegistry;
    private final RedisTemplate<String, String> redis;

    @Value("${app.smpp.server.host:0.0.0.0}")   private String host;
    @Value("${app.smpp.server.port:2775}")        private int port;
    @Value("${app.smpp.server.system-id:MSGAGENT}") private String systemId;
    @Value("${app.smpp.server.password:secret}")  private String password;
    @Value("${app.smpp.server.max-connections:50}") private int maxConnections;

    private DefaultSmppServer smppServer;

    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        SmppServerConfiguration config = new SmppServerConfiguration();
        config.setHost(host);
        config.setPort(port);
        config.setMaxConnectionSize(maxConnections);
        config.setNonBlockingSocketsEnabled(true);
        config.setDefaultRequestExpiryTimeout(30_000);
        config.setDefaultWindowMonitorInterval(15_000);
        config.setDefaultWindowSize(8);
        config.setDefaultWindowWaitTimeout(config.getDefaultRequestExpiryTimeout());
        config.setDefaultSessionCountersEnabled(true);
        config.setJmxEnabled(true);

        smppServer = new DefaultSmppServer(config, new SmppServerHandlerImpl(),
                Executors.newCachedThreadPool());
        try {
            smppServer.start();
            log.info("SMPP Server started on {}:{}", host, port);
        } catch (Exception e) {
            log.error("Failed to start SMPP Server", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (smppServer != null) { smppServer.stop(); log.info("SMPP Server stopped"); }
    }

    // ─── Redis key helpers ────────────────────────────────────────────────────

    public static String sessionKey(String correlationId)  { return "smpp:session:" + correlationId; }
    public static String sourceKey(String correlationId)   { return "smpp:source:"  + correlationId; }

    // ─── Inner handlers ───────────────────────────────────────────────────────

    private class SmppServerHandlerImpl extends DefaultSmppServerHandler {

        @Override
        public void sessionBindRequested(Long sessionId,
                                          SmppSessionConfiguration cfg,
                                          BaseBind bindRequest) throws SmppProcessingException {
            if (!systemId.equals(cfg.getSystemId()) || !password.equals(cfg.getPassword())) {
                throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
            }
            log.info("SMPP bind accepted: systemId={}", cfg.getSystemId());
        }

        @Override
        public void sessionCreated(Long sessionId, SmppServerSession session,
                                    BaseBindResp preparedBindResponse) {
            session.serverReady(new MessageReceiverHandlerImpl(sessionId.toString()));
            sessionRegistry.register(sessionId.toString(), session);
            log.info("SMPP session created id={}", sessionId);
        }

        @Override
        public void sessionDestroyed(Long sessionId, SmppServerSession session) {
            sessionRegistry.unregister(sessionId.toString());
            log.info("SMPP session destroyed id={}", sessionId);
        }
    }

    private class MessageReceiverHandlerImpl implements SmppSessionHandler {

        private final String smppSessionId;

        MessageReceiverHandlerImpl(String smppSessionId) {
            this.smppSessionId = smppSessionId;
        }

        @Override public String lookupResultMessage(int commandStatus) { return null; }
        @Override public String lookupTlvTagName(short tag) { return null; }
        @Override public void fireChannelUnexpectedlyClosed() {
            log.warn("SMPP channel unexpectedly closed (sessionId={})", smppSessionId);
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            if (pduRequest instanceof SubmitSm sm) return handleSubmitSm(sm);
            return pduRequest.createResponse();
        }

        private PduResponse handleSubmitSm(SubmitSm sm) {
            try {
                String srcAddr = sm.getSourceAddress() != null ? sm.getSourceAddress().getAddress() : "";
                String dstAddr = sm.getDestAddress()   != null ? sm.getDestAddress().getAddress()   : "";
                byte[] msgBytes = sm.getShortMessage();
                String msgText  = CharsetUtil.decode(msgBytes,
                        sm.getDataCoding() == 8 ? CharsetUtil.CHARSET_UCS_2 : CharsetUtil.CHARSET_GSM);

                // Assign unique correlation ID for this message
                String correlationId = UUID.randomUUID().toString();

                // ── Store session mapping in Redis (TTL = 300s) ───────────────
                Duration ttl = Duration.ofSeconds(CORRELATION_TTL_SECONDS);
                redis.opsForValue().set(sessionKey(correlationId), smppSessionId, ttl);
                redis.opsForValue().set(sourceKey(correlationId),  srcAddr,       ttl);
                // ─────────────────────────────────────────────────────────────

                log.info("SUBMIT_SM from={} to={} correlationId={}", srcAddr, dstAddr, correlationId);

                SmsInboundEvent event = SmsInboundEvent.builder()
                        .correlationId(correlationId)
                        .sourceAddress(srcAddr)
                        .destinationAddress(dstAddr)
                        .messageText(msgText)
                        .dataCoding(sm.getDataCoding())
                        .timestampMs(System.currentTimeMillis())
                        .build();

                kafkaTemplate.send("sms.inbound", dstAddr, event);

                SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                resp.setMessageId(correlationId);
                return resp;

            } catch (Exception e) {
                log.error("Error processing SUBMIT_SM", e);
                SubmitSmResp resp = (SubmitSmResp) sm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_SYSERR);
                return resp;
            }
        }

        @Override public void fireExpectedPduResponseReceived(PduAsyncResponse r) {}
        @Override public void fireUnexpectedPduResponseReceived(PduResponse r) {}
        @Override public void fireUnrecoverablePduException(UnrecoverablePduException e) {}
        @Override public void fireRecoverablePduException(RecoverablePduException e) {}
        @Override public void fireUnknownThrowable(Throwable t) {}
    }
}
