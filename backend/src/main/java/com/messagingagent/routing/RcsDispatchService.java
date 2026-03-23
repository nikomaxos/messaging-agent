package com.messagingagent.routing;

import com.messagingagent.device.DeviceWebSocketService;
import com.messagingagent.kafka.SmsDeliveryResultEvent;
import com.messagingagent.kafka.SmsInboundEvent;
import com.messagingagent.model.Device;
import com.messagingagent.model.DeviceGroup;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.DeviceGroupRepository;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import com.messagingagent.smpp.SmppResponseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.model.SmppRoutingDestination;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.smpp.SmscConnectionManager;

/**
 * Core routing service. Consumes inbound SMS from Kafka, selects a target
 * Android device via round-robin, and forwards the message via WebSocket.
 *
 * Also consumes delivery results and maps them back to SMPP responses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RcsDispatchService {

    private final RoundRobinLoadBalancer loadBalancer;
    private final DeviceWebSocketService deviceWebSocketService;
    private final SmppResponseService smppResponseService;
    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceRepository deviceRepository;
    private final MessageLogRepository messageLogRepository;
    private final SmppClientRepository smppClientRepository;
    private final SmppRoutingRepository smppRoutingRepository;
    private final SmscConnectionManager smscConnectionManager;

    /**
     * Delay failure DELIVER_SM by 10s to allow a late DELIVERED result to cancel it.
     * If DELIVERED arrives before the 10s elapses, the failure is cancelled.
     */
    private static final int FAILURE_DLR_HOLD_SECONDS = 10;
    private final ScheduledExecutorService failureDlrScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "failure-dlr-hold");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingFailures = new ConcurrentHashMap<>();

    @KafkaListener(topics = "sms.inbound", groupId = "messaging-agent")
    public void handleInboundSms(SmsInboundEvent event) {
        log.info("Routing SMS for systemId={} from={} to={}", event.getSystemId(), event.getSourceAddress(), event.getDestinationAddress());

        RoutingSelection selection = null;

        if (event.getSystemId() != null) {
            var client = smppClientRepository.findBySystemId(event.getSystemId());
            if (client.isPresent()) {
                var routing = smppRoutingRepository.findBySmppClient(client.get());
                if (routing.isPresent()) {
                    selection = selectFromRouting(routing.get());
                }
            }
        }

        // Fallback to default route
        if (selection == null) {
            var defaultRouting = smppRoutingRepository.findByIsDefaultTrue();
            if (defaultRouting.isPresent()) {
                selection = selectFromRouting(defaultRouting.get());
            }
        }

        DeviceGroup group = null;
        if (selection != null) {
            group = selection.deviceGroup;
        }

        // Fallback to any active group if nothing else exists
        if (group == null) {
            List<DeviceGroup> groups = deviceGroupRepository.findByActiveTrue();
            if (!groups.isEmpty()) {
                group = groups.get(0);
                selection = new RoutingSelection();
                selection.deviceGroup = group;
            }
        }

        if (group == null) {
            log.warn("No active device groups available for systemId={}. Dropping message.", event.getSystemId());
            messageLogRepository.save(MessageLog.builder()
                    .smppMessageId(event.getCorrelationId())
                    .sourceAddress(event.getSourceAddress())
                    .destinationAddress(event.getDestinationAddress())
                    .messageText(event.getMessageText())
                    .status(MessageLog.Status.FAILED)
                    .createdAt(Instant.ofEpochMilli(event.getTimestampMs()))
                    .build());
            smppResponseService.sendDeliveryFailure(event.getCorrelationId(), "NO_GROUP");
            return;
        }

        // Always queue the message first, then let the synchronized drainQueueForGroup
        // handle dispatch. This prevents race conditions where multiple Kafka consumer
        // threads select the same device before the BUSY flag is committed.
        MessageLog msgLog = MessageLog.builder()
                .smppMessageId(event.getCorrelationId())
                .customerMessageId(event.getCorrelationId())
                .sourceAddress(event.getSourceAddress())
                .destinationAddress(event.getDestinationAddress())
                .messageText(event.getMessageText())
                .status(MessageLog.Status.QUEUED)
                .device(null)
                .deviceGroup(group)
                .createdAt(Instant.ofEpochMilli(event.getTimestampMs()))
                .rcsExpiresAt(selection != null && selection.rcsExpirationSeconds != null && selection.rcsExpirationSeconds > 0 ? Instant.now().plus(selection.rcsExpirationSeconds, ChronoUnit.SECONDS) : null)
                .resendTrigger(selection != null ? selection.resendTrigger : null)
                .fallbackSmsc(selection != null ? selection.fallbackSmsc : null)
                .build();
        messageLogRepository.save(msgLog);
        log.info("Queued message id={} for group '{}'. Triggering drain.", event.getCorrelationId(), group.getName());

        // Inject DLR delay settings for when the message gets dispatched
        event.setDlrDelayMinSec(group.getDlrDelayMinSec());
        event.setDlrDelayMaxSec(group.getDlrDelayMaxSec());

        // Trigger synchronized queue drain
        deviceWebSocketService.drainQueueForGroup(group);
    }

    @KafkaListener(topics = "sms.delivery.result", groupId = "messaging-agent")
    public void handleDeliveryResult(SmsDeliveryResultEvent result) {
        log.info("Delivery result for correlationId={}: {}", result.getCorrelationId(), result.getResult());

        messageLogRepository.findBySmppMessageId(result.getCorrelationId()).ifPresentOrElse(logEntry -> {
            if (logEntry.getFallbackStartedAt() != null) {
                log.info("Ignoring late delivery result for correlationId={} because fallback already started.", result.getCorrelationId());
                messageLogRepository.save(logEntry);
                return;
            }
            
            // Guard: DELIVERED is the ultimate truth — carrier confirmed delivery is immutable.
            // Also cancel any pending failure DELIVER_SM for this correlationId.
            if (logEntry.getStatus() == MessageLog.Status.DELIVERED) {
                cancelPendingFailure(result.getCorrelationId());
                // Already delivered — but update errorDetail if provided (e.g., SEEN/READ from status=11)
                if (result.getErrorDetail() != null && !result.getErrorDetail().isEmpty()) {
                    logEntry.setErrorDetail(result.getErrorDetail());
                    log.info("Updated errorDetail to '{}' for already-DELIVERED correlationId={}",
                            result.getErrorDetail(), result.getCorrelationId());
                } else {
                    log.info("Ignoring late delivery result ({}) for correlationId={} — already DELIVERED",
                            result.getResult(), result.getCorrelationId());
                }
                messageLogRepository.save(logEntry);
                return;
            }
            if ((logEntry.getStatus() == MessageLog.Status.FAILED || logEntry.getStatus() == MessageLog.Status.RCS_FAILED)
                    && result.getResult() != SmsDeliveryResultEvent.Result.DELIVERED) {
                log.info("Ignoring non-delivery result ({}) for correlationId={} — already terminal: {}",
                        result.getResult(), result.getCorrelationId(), logEntry.getStatus());
                messageLogRepository.save(logEntry);
                return;
            }
            if ((logEntry.getStatus() == MessageLog.Status.FAILED || logEntry.getStatus() == MessageLog.Status.RCS_FAILED)
                    && result.getResult() == SmsDeliveryResultEvent.Result.DELIVERED) {
                cancelPendingFailure(result.getCorrelationId());
                log.info("DELIVERED result overriding {} for correlationId={} — carrier confirmation wins",
                        logEntry.getStatus(), result.getCorrelationId());
                logEntry.setErrorDetail(null); // Clear stale timeout/error detail
                // Fall through to process the DELIVERED result
            }


            // SENT = message submitted to RCS network (status=2 in bugle_db).
            // Don't change message status (stays DISPATCHED), don't send DELIVER_SM.
            // Don't set rcsDlrReceivedAt — that's for the final delivery report only.
            // Device unlock + queue drain happens in DeviceWebSocketService.handleDeliveryResult.
            if (result.getResult() == SmsDeliveryResultEvent.Result.SENT) {
                log.info("SENT signal for correlationId={} — device freed, DLR pending", result.getCorrelationId());
                logEntry.setRcsSentAt(Instant.now());
                messageLogRepository.save(logEntry);
                return;
            }

            // For final DLRs (DELIVERED, ERROR, NO_RCS), record the DLR timestamp
            logEntry.setRcsDlrReceivedAt(Instant.now());

            boolean isFailed = (result.getResult() != SmsDeliveryResultEvent.Result.DELIVERED);
            boolean isNoRcs = (result.getResult() == SmsDeliveryResultEvent.Result.NO_RCS);

            // Send SMPP DLR — DELIVERED immediately, NO_RCS immediately, ERROR with 10s delay
            switch (result.getResult()) {
                case DELIVERED -> {
                    cancelPendingFailure(result.getCorrelationId());
                    smppResponseService.sendDeliverySm(result.getCorrelationId());
                }
                case NO_RCS -> smppResponseService.sendNoRcsFailure(result.getCorrelationId());
                default -> scheduleFailureDlr(result.getCorrelationId(), result.getErrorDetail());
            }

            logEntry.setStatus(switch (result.getResult()) {
                case DELIVERED -> MessageLog.Status.DELIVERED;
                case NO_RCS -> MessageLog.Status.RCS_FAILED;
                default -> MessageLog.Status.FAILED;
            });

            if (result.getErrorDetail() != null && !result.getErrorDetail().isEmpty()) {
                logEntry.setErrorDetail(result.getErrorDetail());
            }

            // Handle immediate fallback if configured
            if (isFailed && logEntry.getFallbackSmsc() != null && logEntry.getResendTrigger() != null) {
                boolean shouldResend = false;
                if ("ALL_FAILURES".equalsIgnoreCase(logEntry.getResendTrigger())) {
                    shouldResend = true;
                } else if ("NO_RCS".equalsIgnoreCase(logEntry.getResendTrigger()) && isNoRcs) {
                    shouldResend = true;
                }

                if (shouldResend) {
                    log.info("Triggering Fallback SMSC (id={}) for correlationId={} due to resendTrigger={}",
                            logEntry.getFallbackSmsc().getId(), logEntry.getSmppMessageId(), logEntry.getResendTrigger());
                    
                    logEntry.setFallbackStartedAt(Instant.now());

                    if (logEntry.getDevice() != null) {
                        deviceWebSocketService.sendSysCommand(logEntry.getDevice(), "CANCEL_RCS=" + logEntry.getDestinationAddress());
                    }

                    String supplierMsgId = smscConnectionManager.submitMessage(
                            logEntry.getFallbackSmsc().getId(), 
                            logEntry.getSourceAddress(), 
                            logEntry.getDestinationAddress(), 
                            logEntry.getMessageText());
                            
                    if (supplierMsgId != null) {
                        logEntry.setStatus(MessageLog.Status.DELIVERED); // Mark delivered since it's offloaded 
                        logEntry.setSupplierMessageId(supplierMsgId);
                        smppResponseService.sendDeliverySm(result.getCorrelationId());
                    }
                }
            }

            messageLogRepository.save(logEntry);
        }, () -> log.warn("Received delivery result for unknown correlationId={}", result.getCorrelationId()));
    }

    private static class RoutingSelection {
        DeviceGroup deviceGroup;
        SmscSupplier fallbackSmsc;
        Integer rcsExpirationSeconds;
        String resendTrigger;

        RoutingSelection() {}

        RoutingSelection(DeviceGroup deviceGroup, SmscSupplier fallbackSmsc, Integer rcsExpirationSeconds, String resendTrigger) {
            this.deviceGroup = deviceGroup;
            this.fallbackSmsc = fallbackSmsc;
            this.rcsExpirationSeconds = rcsExpirationSeconds;
            this.resendTrigger = resendTrigger;
        }
    }

    private RoutingSelection selectFromRouting(SmppRouting routing) {
        if (routing.getDestinations().isEmpty()) {
            return null;
        }

        SmppRoutingDestination chosenDest = null;
        if (!routing.isLoadBalancerEnabled()) {
            chosenDest = routing.getDestinations().iterator().next();
        } else {
            int totalWeight = routing.getDestinations().stream().mapToInt(SmppRoutingDestination::getWeightPercent).sum();
            if (totalWeight <= 0) {
                chosenDest = routing.getDestinations().iterator().next();
            } else {
                int randomValue = new Random().nextInt(totalWeight);
                int currentWeightSum = 0;
                for (var dest : routing.getDestinations()) {
                    currentWeightSum += dest.getWeightPercent();
                    if (randomValue < currentWeightSum) {
                        chosenDest = dest;
                        break;
                    }
                }
                if (chosenDest == null) {
                    chosenDest = routing.getDestinations().iterator().next();
                }
            }
        }

        SmscSupplier fallback = null;
        String resendTrigger = null;
        Integer rcsExpirationSeconds = null;

        if (routing.isResendEnabled()) {
            fallback = chosenDest.getFallbackSmsc() != null ? chosenDest.getFallbackSmsc() : routing.getFallbackSmsc();
            resendTrigger = routing.getResendTrigger();
            rcsExpirationSeconds = routing.getRcsExpirationSeconds();
        }

        return new RoutingSelection(chosenDest.getDeviceGroup(), fallback, rcsExpirationSeconds, resendTrigger);
    }

    /**
     * Schedule a failure DELIVER_SM to be sent after FAILURE_DLR_HOLD_SECONDS.
     * If DELIVERED arrives before the timer fires, the failure is cancelled.
     */
    private void scheduleFailureDlr(String correlationId, String errorDetail) {
        // Cancel any existing pending failure for this correlationId (shouldn't happen, but be safe)
        cancelPendingFailure(correlationId);

        log.info("Scheduling failure DELIVER_SM for correlationId={} in {}s (errorDetail={})",
                correlationId, FAILURE_DLR_HOLD_SECONDS, errorDetail);

        ScheduledFuture<?> future = failureDlrScheduler.schedule(() -> {
            pendingFailures.remove(correlationId);
            log.info("Failure DLR hold expired for correlationId={} — sending failure DELIVER_SM now", correlationId);
            smppResponseService.sendDeliveryFailure(correlationId, errorDetail);
        }, FAILURE_DLR_HOLD_SECONDS, TimeUnit.SECONDS);

        pendingFailures.put(correlationId, future);
    }

    /**
     * Cancel a pending failure DELIVER_SM if DELIVERED arrives first.
     */
    private void cancelPendingFailure(String correlationId) {
        ScheduledFuture<?> pending = pendingFailures.remove(correlationId);
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
            log.info("Cancelled pending failure DELIVER_SM for correlationId={} — DELIVERED arrived first", correlationId);
        }
    }
}
