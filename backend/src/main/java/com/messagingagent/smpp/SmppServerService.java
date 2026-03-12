package com.messagingagent.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppServerHandler;
import com.cloudhopper.smpp.impl.DefaultSmppSession;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.messagingagent.kafka.SmsInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;

/**
 * SMPP Server Service using CloudHopper.
 * Listens for SUBMIT_SM PDUs from upstream requesters (MVNO/aggregator).
 * Each received PDU is forwarded to Kafka topic 'sms.inbound' for processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmppServerService {

    private final KafkaTemplate<String, SmsInboundEvent> kafkaTemplate;
    private final SmppSessionRegistry sessionRegistry;

    @Value("${app.smpp.server.host:0.0.0.0}")
    private String host;

    @Value("${app.smpp.server.port:2775}")
    private int port;

    @Value("${app.smpp.server.system-id:MSGAGENT}")
    private String systemId;

    @Value("${app.smpp.server.password:secret}")
    private String password;

    @Value("${app.smpp.server.max-connections:50}")
    private int maxConnections;

    private DefaultSmppServer smppServer;

    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        SmppServerConfiguration config = new SmppServerConfiguration();
        config.setHost(host);
        config.setPort(port);
        config.setMaxConnectionSize(maxConnections);
        config.setNonBlockingSocketsEnabled(true);
        config.setDefaultRequestExpiryTimeout(30000);
        config.setDefaultWindowMonitorInterval(15000);
        config.setDefaultWindowSize(8);
        config.setDefaultWindowWaitTimeout(config.getDefaultRequestExpiryTimeout());
        config.setDefaultSessionCountersEnabled(true);
        config.setJmxEnabled(true);

        smppServer = new DefaultSmppServer(config, new SmppServerHandlerImpl(), Executors.newCachedThreadPool());
        try {
            smppServer.start();
            log.info("SMPP Server started on {}:{}", host, port);
        } catch (Exception e) {
            log.error("Failed to start SMPP Server", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (smppServer != null) {
            smppServer.stop();
            log.info("SMPP Server stopped");
        }
    }

    private class SmppServerHandlerImpl extends DefaultSmppServerHandler {

        @Override
        public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration,
                                         BaseBind bindRequest) throws SmppProcessingException {

            String reqSystemId = sessionConfiguration.getSystemId();
            String reqPassword = sessionConfiguration.getPassword();

            // TODO: Look up system_id + password from DB (SmppConfig table)
            // For now use application-level credentials
            if (!systemId.equals(reqSystemId) || !password.equals(reqPassword)) {
                throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD);
            }
            log.info("SMPP bind accepted for systemId={}", reqSystemId);
        }

        @Override
        public void sessionCreated(Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse) {
            session.serverReady(new MessageReceiverHandlerImpl());
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

        @Override
        public String lookupResultMessage(int commandStatus) { return null; }

        @Override
        public String lookupTlvTagName(short tag) { return null; }

        @Override
        public void fireChannelUnexpectedlyClosed() {
            log.warn("SMPP channel unexpectedly closed");
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            if (pduRequest instanceof SubmitSm submitSm) {
                return handleSubmitSm(submitSm);
            }
            return pduRequest.createResponse();
        }

        private PduResponse handleSubmitSm(SubmitSm submitSm) {
            try {
                String srcAddr = submitSm.getSourceAddress() != null ?
                        submitSm.getSourceAddress().getAddress() : "";
                String dstAddr = submitSm.getDestAddress() != null ?
                        submitSm.getDestAddress().getAddress() : "";
                byte[] msgBytes = submitSm.getShortMessage();
                String msgText = CharsetUtil.decode(msgBytes,
                        submitSm.getDataCoding() == 8 ? CharsetUtil.CHARSET_UCS_2 : CharsetUtil.CHARSET_GSM);

                log.info("SUBMIT_SM received: from={} to={} msg={}", srcAddr, dstAddr, msgText);

                SmsInboundEvent event = SmsInboundEvent.builder()
                        .sourceAddress(srcAddr)
                        .destinationAddress(dstAddr)
                        .messageText(msgText)
                        .dataCoding(submitSm.getDataCoding())
                        .build();

                kafkaTemplate.send("sms.inbound", dstAddr, event);

                SubmitSmResp resp = (SubmitSmResp) submitSm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_OK);
                resp.setMessageId(String.valueOf(System.currentTimeMillis()));
                return resp;

            } catch (Exception e) {
                log.error("Error processing SUBMIT_SM", e);
                SubmitSmResp resp = (SubmitSmResp) submitSm.createResponse();
                resp.setCommandStatus(SmppConstants.STATUS_SYSERR);
                return resp;
            }
        }

        @Override public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {}
        @Override public void fireUnexpectedPduResponseReceived(PduResponse pduResponse) {}
        @Override public void fireUnrecoverablePduException(UnrecoverablePduException e) {}
        @Override public void fireRecoverablePduException(RecoverablePduException e) {}
        @Override public void fireUnknownThrowable(Throwable t) {}
    }
}
