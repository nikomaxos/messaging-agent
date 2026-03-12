package com.messagingagent.smpp;

import com.cloudhopper.smpp.SmppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for sending SMPP responses back to upstream clients.
 *
 * RCS_NO_CAPABILITY is signalled using ESME_RDELIVERYFAILURE (0x00000011)
 * which is the standard SMPP 3.4 delivery failure error code.
 * A custom TLV (0x1400) carries the sub-error code 0x01 = NO_RCS_CAPABILITY.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SmppResponseService {

    private final SmppSessionRegistry sessionRegistry;

    /** RCS delivery confirmed – send DELIVER_SM to upstream. */
    public void sendDeliverySm(String correlationId) {
        log.info("RCS delivery confirmed for correlationId={}", correlationId);
        // TODO: find corresponding upstream session and send DELIVER_SM PDU
        // Requires storing smpp session id alongside correlationId in Redis or DB
    }

    /**
     * RCS not supported – return ESME_RDELIVERYFAILURE to the upstream.
     * Sub-error code 0x01 in TLV 0x1400 = NO_RCS_CAPABILITY.
     * This triggers fail-over to plain SMS on the requester side.
     */
    public void sendNoRcsFailure(String correlationId) {
        log.warn("No RCS capability for correlationId={} — signalling ESME_RDELIVERYFAILURE(0x00000011) + TLV 0x1400=0x01",
                correlationId);
        // TODO: retrieve active SMPP upstream session and send appropriate error PDU
    }

    /** Generic delivery failure. */
    public void sendDeliveryFailure(String correlationId, String reason) {
        log.error("Delivery failure for correlationId={} reason={}", correlationId, reason);
        // TODO: send ESME_RDELIVERYFAILURE to upstream
    }
}
