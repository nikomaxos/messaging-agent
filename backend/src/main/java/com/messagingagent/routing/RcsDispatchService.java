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

    @KafkaListener(topics = "sms.inbound", groupId = "messaging-agent")
    public void handleInboundSms(SmsInboundEvent event) {
        log.info("Routing SMS from={} to={}", event.getSourceAddress(), event.getDestinationAddress());

        // Find an active group (use first active group by default;
        // production: route by destination prefix or config)
        List<DeviceGroup> groups = deviceGroupRepository.findByActiveTrue();
        if (groups.isEmpty()) {
            log.warn("No active device groups. Dropping message.");
            smppResponseService.sendDeliveryFailure(event.getCorrelationId(), "NO_GROUP");
            return;
        }

        DeviceGroup group = groups.get(0);
        List<Device> onlineDevices = deviceRepository.findByGroupAndStatus(group, Device.Status.ONLINE);

        Optional<Device> selectedDevice = loadBalancer.selectDevice(group, onlineDevices);
        if (selectedDevice.isEmpty()) {
            log.warn("No online devices in group '{}'. Delivery failure.", group.getName());
            smppResponseService.sendDeliveryFailure(event.getCorrelationId(), "NO_DEVICE");
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
