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

        List<Device> onlineDevices = deviceRepository.findByGroupAndStatus(group, Device.Status.ONLINE);

        Optional<Device> selectedDevice = loadBalancer.selectDevice(group, onlineDevices);
        if (selectedDevice.isEmpty()) {
            log.info("No online/free devices in group '{}'. Queueing message id={}.", group.getName(), event.getCorrelationId());
            MessageLog msgLog = MessageLog.builder()
                    .smppMessageId(event.getCorrelationId())
                    .sourceAddress(event.getSourceAddress())
                    .destinationAddress(event.getDestinationAddress())
                    .messageText(event.getMessageText())
                    .status(MessageLog.Status.QUEUED)
                    .device(null) // No device assigned yet
                    .deviceGroup(group) // Attach to the virtual SMSC for queue draining
                    .createdAt(Instant.ofEpochMilli(event.getTimestampMs()))
                    .rcsExpiresAt(selection != null && selection.rcsExpirationSeconds != null && selection.rcsExpirationSeconds > 0 ? Instant.now().plus(selection.rcsExpirationSeconds, ChronoUnit.SECONDS) : null)
                    .resendTrigger(selection != null ? selection.resendTrigger : null)
                    .fallbackSmsc(selection != null ? selection.fallbackSmsc : null)
                    .build();
            // Let the device entity reference the device group through a proxy field, or simply rely on the queue drain query
            // Since MessageLog has a reference to Device but not DeviceGroup directly, we can't cleanly query "QUEUED messages for a DeviceGroup".
            // WAIT - the current query `findFirstByStatusAndDeviceGroupIdOrderByCreatedAtAsc` relies on `m.device.group.id`, but if `m.device` is null, that JOIN fails!
            // I need to add `DeviceGroup` to the `MessageLog` schema. Let me just use a simpler design: queue per-group using an explicit column.
            messageLogRepository.save(msgLog);
            
            // Wait, MessageLog schema *doesn't* have a `group` column. I must add `DeviceGroup` to `MessageLog` schema. Let me rollback this logic for a moment.
            return;
        }

        Device device = selectedDevice.get();
        log.info("Dispatching to device id={} name={}", device.getId(), device.getName());
        loadBalancer.recordDispatch(device.getId());
        
        // Lock the device to BUSY
        device.setStatus(Device.Status.BUSY);
        deviceRepository.save(device);

        Instant expiresAt = null;
        if (selection != null && selection.rcsExpirationSeconds != null && selection.rcsExpirationSeconds > 0) {
            expiresAt = Instant.now().plus(selection.rcsExpirationSeconds, ChronoUnit.SECONDS);
        }

        // Persist message log
        MessageLog msgLog = MessageLog.builder()
                .smppMessageId(event.getCorrelationId())
                .customerMessageId(event.getCorrelationId())
                .sourceAddress(event.getSourceAddress())
                .destinationAddress(event.getDestinationAddress())
                .messageText(event.getMessageText())
                .status(MessageLog.Status.DISPATCHED)
                .device(device)
                .deviceGroup(group)
                .createdAt(Instant.ofEpochMilli(event.getTimestampMs()))
                .rcsExpiresAt(expiresAt)
                .resendTrigger(selection != null ? selection.resendTrigger : null)
                .fallbackSmsc(selection != null ? selection.fallbackSmsc : null)
                .dispatchedAt(Instant.now())
                .build();
        messageLogRepository.save(msgLog);

        // Inject per-group DLR delay configuration into the dispatch payload
        event.setDlrDelayMinSec(group.getDlrDelayMinSec());
        event.setDlrDelayMaxSec(group.getDlrDelayMaxSec());

        // Send to device via WebSocket
        deviceWebSocketService.sendSmsToDevice(device, event);
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
            // But if current state is FAILED/RCS_FAILED (e.g. from expiration timeout),
            // a DELIVERED result from the APK should override it — the message WAS delivered.
            if (logEntry.getStatus() == MessageLog.Status.DELIVERED) {
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

            switch (result.getResult()) {
                case DELIVERED -> smppResponseService.sendDeliverySm(result.getCorrelationId());
                case NO_RCS -> smppResponseService.sendNoRcsFailure(result.getCorrelationId());
                default -> smppResponseService.sendDeliveryFailure(result.getCorrelationId(), result.getErrorDetail());
            }

            boolean isFailed = (result.getResult() != SmsDeliveryResultEvent.Result.DELIVERED);
            boolean isNoRcs = (result.getResult() == SmsDeliveryResultEvent.Result.NO_RCS);

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
}
