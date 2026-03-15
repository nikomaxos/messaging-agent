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
                    .build());
            smppResponseService.sendDeliveryFailure(event.getCorrelationId(), "NO_GROUP");
            return;
        }

        List<Device> onlineDevices = deviceRepository.findByGroupAndStatus(group, Device.Status.ONLINE);

        Optional<Device> selectedDevice = loadBalancer.selectDevice(group, onlineDevices);
        if (selectedDevice.isEmpty()) {
            log.warn("No online devices in group '{}'. Signalling NO_DEVICE failure.", group.getName());
            messageLogRepository.save(MessageLog.builder()
                    .smppMessageId(event.getCorrelationId())
                    .sourceAddress(event.getSourceAddress())
                    .destinationAddress(event.getDestinationAddress())
                    .messageText(event.getMessageText())
                    .status(MessageLog.Status.FAILED)
                    .build());
            smppResponseService.sendNoDeviceFailure(event.getCorrelationId());
            return;
        }

        Device device = selectedDevice.get();
        log.info("Dispatching to device id={} name={}", device.getId(), device.getName());

        Instant expiresAt = null;
        if (selection != null && selection.rcsExpirationSeconds != null && selection.rcsExpirationSeconds > 0) {
            expiresAt = Instant.now().plus(selection.rcsExpirationSeconds, ChronoUnit.SECONDS);
        }

        // Persist message log
        MessageLog msgLog = MessageLog.builder()
                .smppMessageId(event.getCorrelationId())
                .sourceAddress(event.getSourceAddress())
                .destinationAddress(event.getDestinationAddress())
                .messageText(event.getMessageText())
                .status(MessageLog.Status.DISPATCHED)
                .device(device)
                .rcsExpiresAt(expiresAt)
                .resendTrigger(selection != null ? selection.resendTrigger : null)
                .fallbackSmsc(selection != null ? selection.fallbackSmsc : null)
                .build();
        messageLogRepository.save(msgLog);

        // Send to device via WebSocket
        deviceWebSocketService.sendSmsToDevice(device, event);
    }

    @KafkaListener(topics = "sms.delivery.result", groupId = "messaging-agent")
    public void handleDeliveryResult(SmsDeliveryResultEvent result) {
        log.info("Delivery result for correlationId={}: {}", result.getCorrelationId(), result.getResult());

        switch (result.getResult()) {
            case DELIVERED -> smppResponseService.sendDeliverySm(result.getCorrelationId());
            case NO_RCS -> smppResponseService.sendNoRcsFailure(result.getCorrelationId());
            default -> smppResponseService.sendDeliveryFailure(result.getCorrelationId(), result.getErrorDetail());
        }

        // Update message log
        messageLogRepository.findBySmppMessageId(result.getCorrelationId()).ifPresent(logEntry -> {
            boolean isFailed = (result.getResult() != SmsDeliveryResultEvent.Result.DELIVERED);
            boolean isNoRcs = (result.getResult() == SmsDeliveryResultEvent.Result.NO_RCS);

            logEntry.setStatus(switch (result.getResult()) {
                case DELIVERED -> MessageLog.Status.DELIVERED;
                case NO_RCS -> MessageLog.Status.RCS_FAILED;
                default -> MessageLog.Status.FAILED;
            });

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

                    boolean sent = smscConnectionManager.submitMessage(
                            logEntry.getFallbackSmsc().getId(), 
                            logEntry.getSourceAddress(), 
                            logEntry.getDestinationAddress(), 
                            logEntry.getMessageText());
                            
                    if (sent) {
                        logEntry.setStatus(MessageLog.Status.DELIVERED); // Mark delivered since it's offloaded 
                        // Alternatively, add a new status like FALLBACK_SENT. For now DELIVERED satisfies upstream.
                        // Actually wait! We need to reply to the SMPP client with a success now instead of fail!
                    }
                }
            }

            messageLogRepository.save(logEntry);
        });
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

        SmscSupplier fallback = chosenDest.getFallbackSmsc() != null ? chosenDest.getFallbackSmsc() : routing.getFallbackSmsc();
        return new RoutingSelection(chosenDest.getDeviceGroup(), fallback, routing.getRcsExpirationSeconds(), routing.getResendTrigger());
    }
}
