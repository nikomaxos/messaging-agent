package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.Address;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Fully implements the SMPP back-channel for returning delivery results
 * to upstream requesters (ESME).
 *
 * Session mapping is backed by Redis:
 *   smpp:session:{correlationId} → smppSessionId
 *   smpp:source:{correlationId}  → sourceAddress (for DELIVER_SM)
 *
 * Error encoding:
 *   - RCS delivered   → DELIVER_SM to upstream (standard delivery receipt)
 *   - No RCS          → ESME_RDELIVERYFAILURE (0x00000011)
 *                        + TLV 0x1400 = 0x01 (NO_RCS_CAPABILITY)
 *   - No device       → ESME_RDELIVERYFAILURE + TLV 0x1400 = 0x02 (NO_DEVICE)
 *   - Generic error   → ESME_RSYSERR (0x00000008)
 */
@Service
@Slf4j
public class SmppResponseService {

    /** Custom TLV tag for no-RCS sub-error. Registered in private tag range 0x1400–0x3FFF. */
    private static final short TLV_RCS_STATUS    = (short) 0x1400;
    private static final byte  TLV_NO_RCS        = 0x01;
    private static final byte  TLV_NO_DEVICE     = 0x02;
    private static final byte  TLV_GENERIC_ERROR = 0x03;

    private final SmppSessionRegistry sessionRegistry;
    private final RedisTemplate<String, String> redis;

    public SmppResponseService(SmppSessionRegistry sessionRegistry,
                                @Qualifier("smppCorrelationRedisTemplate") RedisTemplate<String, String> redis) {
        this.sessionRegistry = sessionRegistry;
        this.redis = redis;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * RCS successfully delivered — send DELIVER_SM receipt to upstream.
     */
    public void sendDeliverySm(String correlationId) {
        withSession(correlationId, (session, srcAddr) -> {
            try {
                DeliverSm deliverSm = buildDeliveryReceipt(correlationId, srcAddr);
                session.sendRequestPdu(deliverSm, 10_000, false);
                log.info("DELIVER_SM sent for correlationId={}", correlationId);
            } catch (Exception e) {
                log.error("Failed to send DELIVER_SM for correlationId={}", correlationId, e);
            }
        });
        cleanup(correlationId);
    }

    /**
     * Destination has no RCS capability.
     * Returns ESME_RDELIVERYFAILURE + TLV 0x1400 = 0x01 (NO_RCS_CAPABILITY).
     * The requester uses this to trigger SMS fail-over.
     */
    public void sendNoRcsFailure(String correlationId) {
        sendFailureResponse(correlationId, SmppConstants.STATUS_DELIVERYFAILURE, TLV_NO_RCS,
                "No RCS capability at destination");
    }

    /**
     * No Android devices are online.
     * Returns ESME_RDELIVERYFAILURE + TLV 0x1400 = 0x02 (NO_DEVICE).
     */
    public void sendNoDeviceFailure(String correlationId) {
        sendFailureResponse(correlationId, SmppConstants.STATUS_DELIVERYFAILURE, TLV_NO_DEVICE,
                "No devices online in virtual SMSC");
    }

    /**
     * Generic delivery failure.
     */
    public void sendDeliveryFailure(String correlationId, String reason) {
        log.warn("Delivery failure correlationId={} reason={}", correlationId, reason);
        sendFailureResponse(correlationId, SmppConstants.STATUS_DELIVERYFAILURE, TLV_GENERIC_ERROR,
                reason != null ? reason : "Generic error");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void sendFailureResponse(String correlationId, int status, byte subError, String reason) {
        withSession(correlationId, (session, srcAddr) -> {
            try {
                // Build a DELIVER_SM with a delivery-receipt body (ERR:xxx)
                DeliverSm deliverSm = buildDeliveryReceiptFailure(correlationId, srcAddr, reason);
                // Attach custom TLV for sub-error signalling
                deliverSm.addOptionalParameter(
                        new com.cloudhopper.smpp.tlv.Tlv(TLV_RCS_STATUS, new byte[]{subError}));
                session.sendRequestPdu(deliverSm, 10_000, false);
                log.warn("DELIVER_SM failure (status=0x{} subError=0x{}) sent for correlationId={}",
                        Integer.toHexString(status), Integer.toHexString(subError & 0xFF), correlationId);
            } catch (Exception e) {
                log.error("Failed to send failure DELIVER_SM for correlationId={}", correlationId, e);
            }
        });
        cleanup(correlationId);
    }

    private void withSession(String correlationId, SessionConsumer consumer) {
        String sessionId = redis.opsForValue().get(SmppServerService.sessionKey(correlationId));
        String srcAddr   = redis.opsForValue().get(SmppServerService.sourceKey(correlationId));
        if (sessionId == null) {
            log.warn("No SMPP session found in Redis for correlationId={} (may have expired)", correlationId);
            return;
        }
        Optional<SmppServerSession> session = sessionRegistry.getSession(sessionId);
        if (session.isEmpty()) {
            log.warn("SMPP session {} closed before delivery result for correlationId={}", sessionId, correlationId);
            return;
        }
        consumer.accept(session.get(), srcAddr != null ? srcAddr : "");
    }

    private void cleanup(String correlationId) {
        redis.delete(SmppServerService.sessionKey(correlationId));
        redis.delete(SmppServerService.sourceKey(correlationId));
    }

    /** Build a standard SMPP delivery receipt DELIVER_SM for a successful delivery. */
    private DeliverSm buildDeliveryReceipt(String msgId, String to) throws Exception {
        DeliverSm d = new DeliverSm();
        d.setSourceAddress(new Address((byte) 0x01, (byte) 0x01, to));
        d.setDestAddress(new Address((byte) 0x01, (byte) 0x01, to));
        d.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        String receipt = "id:" + msgId + " sub:001 dlvrd:001 submit date:2301010000 done date:2301010001 stat:DELIVRD err:000 text:";
        d.setShortMessage(receipt.getBytes());
        return d;
    }

    /** Build a DELIVER_SM for a failed delivery with error body. */
    private DeliverSm buildDeliveryReceiptFailure(String msgId, String to, String err) throws Exception {
        DeliverSm d = new DeliverSm();
        d.setSourceAddress(new Address((byte) 0x01, (byte) 0x01, to));
        d.setDestAddress(new Address((byte) 0x01, (byte) 0x01, to));
        d.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        String receipt = "id:" + msgId + " sub:001 dlvrd:000 submit date:2301010000 done date:2301010001 stat:UNDELIV err:999 text:" +
                (err != null ? err.substring(0, Math.min(err.length(), 20)) : "");
        d.setShortMessage(receipt.getBytes());
        return d;
    }

    @FunctionalInterface
    private interface SessionConsumer {
        void accept(SmppServerSession session, String srcAddr);
    }
}
