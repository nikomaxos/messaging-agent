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

import java.util.List;
import java.util.Optional;

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

    @KafkaListener(topics = "sms.inbound", groupId = "messaging-agent")
    public void handleInboundSms(SmsInboundEvent event) {
        log.info("Routing SMS for systemId={} from={} to={}", event.getSystemId(), event.getSourceAddress(), event.getDestinationAddress());

        DeviceGroup group = null;

        if (event.getSystemId() != null) {
            var client = smppClientRepository.findBySystemId(event.getSystemId());
            if (client.isPresent()) {
                var routing = smppRoutingRepository.findBySmppClient(client.get());
                if (routing.isPresent()) {
                    group = routing.get().getDeviceGroup();
                }
            }
        }

        // Fallback to default route
        if (group == null) {
            var defaultRouting = smppRoutingRepository.findByIsDefaultTrue();
            if (defaultRouting.isPresent()) {
                group = defaultRouting.get().getDeviceGroup();
            }
        }

        // Fallback to any active group if nothing else exists
        if (group == null) {
            List<DeviceGroup> groups = deviceGroupRepository.findByActiveTrue();
            if (!groups.isEmpty()) {
                group = groups.get(0);
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

        // Persist message log
        MessageLog msgLog = MessageLog.builder()
                .smppMessageId(event.getCorrelationId())
                .sourceAddress(event.getSourceAddress())
                .destinationAddress(event.getDestinationAddress())
                .messageText(event.getMessageText())
                .status(MessageLog.Status.DISPATCHED)
                .device(device)
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
        messageLogRepository.findBySmppMessageId(result.getCorrelationId()).ifPresent(log -> {
            log.setStatus(switch (result.getResult()) {
                case DELIVERED -> MessageLog.Status.DELIVERED;
                case NO_RCS -> MessageLog.Status.RCS_FAILED;
                default -> MessageLog.Status.FAILED;
            });
            messageLogRepository.save(log);
        });
    }
}
