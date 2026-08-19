package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmppClient;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import java.util.ArrayList;
import java.util.List;
import com.messagingagent.model.SmppRoutingDestination;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsInboundConsumer {

    private final SmppClientRepository smppClientRepository;
    private final SmppRoutingRepository smppRoutingRepository;
    private final MessageLogRepository messageLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = "sms.inbound.raw", groupId = "core-service-raw-group")
    public void consumeRawInbound(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String systemId = (String) event.get("systemId");
            String correlationId = (String) event.get("correlationId");
            String sourceAddress = (String) event.get("sourceAddress");
            String destinationAddress = (String) event.get("destinationAddress");
            String messageText = (String) event.get("messageText");

            SmppClient smppClient = null;
            if (systemId != null) {
                smppClient = smppClientRepository.findBySystemId(systemId).orElse(null);
            }

            if (messageLogRepository.findBySmppMessageId(correlationId).isEmpty()) {
                MessageLog logEntry = MessageLog.builder()
                        .smppMessageId(correlationId)
                        .customerMessageId(correlationId)
                        .smppClient(smppClient)
                        .sourceAddress(sourceAddress)
                        .destinationAddress(destinationAddress)
                        .messageText(messageText)
                        .status(MessageLog.Status.RECEIVED)
                        .isEmulated(false)
                        .build();
                messageLogRepository.save(logEntry);
            }
        } catch (Exception e) {
            log.error("Failed to process sms.inbound.raw message", e);
        }
    }

    @KafkaListener(topics = "sms.inbound", groupId = "core-service-routing-group")
    public void consumeInbound(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String systemId = (String) event.get("systemId");
            String correlationId = (String) event.get("correlationId");
            String sourceAddress = (String) event.get("sourceAddress");
            String destinationAddress = (String) event.get("destinationAddress");
            String messageText = (String) event.get("messageText");

            log.info("Routing engine evaluating message correlationId={} systemId={}", correlationId, systemId);

            String rejectionReason = (String) event.get("rejectionReason");
            if (rejectionReason != null) {
                log.info("Received edge-rejected message for correlationId={}: {}", correlationId, rejectionReason);
                MessageLog logEntry = MessageLog.builder()
                        .smppMessageId(correlationId)
                        .customerMessageId(correlationId)
                        .smppClient(smppClientRepository.findBySystemId(systemId).orElse(null))
                        .sourceAddress(sourceAddress)
                        .destinationAddress(destinationAddress)
                        .messageText(messageText)
                        .status(MessageLog.Status.FAILED)
                        .isEmulated(false)
                        .errorDetail("REJECTED_AT_EDGE: " + rejectionReason)
                        .routingMode(com.messagingagent.model.RoutingMode.SMS)
                        .build();
                messageLogRepository.save(logEntry);
                return;
            }

            Optional<SmppClient> clientOpt = smppClientRepository.findBySystemId(systemId);
            SmppRouting routing = null;

            if (clientOpt.isPresent()) {
                routing = smppRoutingRepository.findBySmppClient(clientOpt.get()).orElse(null);
            }
            if (routing == null) {
                routing = smppRoutingRepository.findByIsDefaultTrue().orElse(null);
            }

            if (routing != null && routing.isEmulateDelivery()) {
                log.info("Emulating delivery for correlationId={} with code={}", correlationId, routing.getEmulatedErrorCode());
                
                MessageLog.Status status = MessageLog.Status.DELIVERED;
                if (routing.getEmulatedErrorCode() != null && !routing.getEmulatedErrorCode().equalsIgnoreCase("DELIVRD")) {
                    status = MessageLog.Status.FAILED;
                }

                MessageLog logEntry = MessageLog.builder()
                        .smppMessageId(correlationId)
                        .customerMessageId(correlationId)
                        .smppClient(clientOpt.orElse(null))
                        .sourceAddress(sourceAddress)
                        .destinationAddress(destinationAddress)
                        .messageText(messageText)
                        .status(status)
                        .isEmulated(true)
                        .errorDetail("EMULATED " + (routing.getEmulatedErrorCode() != null ? routing.getEmulatedErrorCode() : "DELIVRD"))
                        .build();

                messageLogRepository.save(logEntry);
                
                // Send fake DLR back to smpp-edge
                String dlrStatus = (status == MessageLog.Status.DELIVERED) ? "DELIVERED" : "FAILED";
                String dlrJson = String.format("{\"correlationId\":\"%s\", \"status\":\"%s\", \"reason\":\"%s\"}", 
                        correlationId, dlrStatus, routing.getEmulatedErrorCode());
                kafkaTemplate.send("sms.delivery.receipt", correlationId, dlrJson);
                
                return;
            }

            // Normal routing
            MessageLog logEntry = messageLogRepository.findBySmppMessageId(correlationId)
                    .orElseGet(() -> MessageLog.builder()
                            .smppMessageId(correlationId)
                            .customerMessageId(correlationId)
                            .smppClient(clientOpt.orElse(null))
                            .sourceAddress(sourceAddress)
                            .destinationAddress(destinationAddress)
                            .messageText(messageText)
                            .isEmulated(false)
                            .build());

            logEntry.setStatus(MessageLog.Status.QUEUED);
            if (logEntry.getSmppClient() == null && clientOpt.isPresent()) {
                logEntry.setSmppClient(clientOpt.get());
            }

            if (routing != null) {
                logEntry.setRoutingMode(routing.getRoutingMode());

                if (!routing.getDestinations().isEmpty()) {
                    List<SmppRoutingDestination> destinations = new ArrayList<>(routing.getDestinations());
                    SmppRoutingDestination selectedDest = selectDestinationByWeight(destinations);

                    if (selectedDest != null) {
                        logEntry.setFallbackRoutingMode(selectedDest.getFallbackRoutingMode() != null ? selectedDest.getFallbackRoutingMode() : routing.getFallbackRoutingMode());
                        logEntry.setFallbackErrorCodes(selectedDest.getFallbackErrorCodes() != null ? selectedDest.getFallbackErrorCodes() : routing.getFallbackErrorCodes());

                        if (routing.getRoutingMode() == com.messagingagent.model.RoutingMode.SMS) {
                            logEntry.setFallbackSmsc(selectedDest.getFallbackSmsc());
                            logEntry.setFallbackDeviceGroup(selectedDest.getFallbackDeviceGroup());
                        } else {
                            logEntry.setDeviceGroup(selectedDest.getDeviceGroup());
                            if (selectedDest.getFallbackSmsc() != null || selectedDest.getFallbackDeviceGroup() != null) {
                                logEntry.setFallbackSmsc(selectedDest.getFallbackSmsc());
                                logEntry.setFallbackDeviceGroup(selectedDest.getFallbackDeviceGroup());
                            } else {
                                logEntry.setFallbackSmsc(routing.getFallbackSmsc());
                                logEntry.setFallbackDeviceGroup(routing.getFallbackDeviceGroup());
                            }
                        }
                    }
                } else {
                    logEntry.setFallbackRoutingMode(routing.getFallbackRoutingMode());
                    logEntry.setFallbackErrorCodes(routing.getFallbackErrorCodes());
                    logEntry.setFallbackSmsc(routing.getFallbackSmsc());
                    logEntry.setFallbackDeviceGroup(routing.getFallbackDeviceGroup());
                }
            }

            messageLogRepository.save(logEntry);
            log.info("Saved message correlationId={} as QUEUED for dispatch", correlationId);

            if (logEntry.getRoutingMode() == com.messagingagent.model.RoutingMode.SMS && logEntry.getFallbackSmsc() != null) {
                Map<String, Object> outboundEvent = Map.of(
                    "correlationId", correlationId,
                    "supplierId", logEntry.getFallbackSmsc().getId(),
                    "sourceAddress", sourceAddress,
                    "destinationAddress", destinationAddress,
                    "messageText", messageText
                );
                kafkaTemplate.send("outbound.smpp", correlationId, objectMapper.writeValueAsString(outboundEvent));
                log.info("Dispatched to outbound.smpp for correlationId={} supplierId={}", correlationId, logEntry.getFallbackSmsc().getId());
            }

        } catch (Exception e) {
            log.error("Failed to process sms.inbound message", e);
        }
    }

    private SmppRoutingDestination selectDestinationByWeight(List<SmppRoutingDestination> destinations) {
        if (destinations == null || destinations.isEmpty()) return null;
        if (destinations.size() == 1) return destinations.get(0);

        int totalWeight = destinations.stream().mapToInt(SmppRoutingDestination::getWeightPercent).sum();
        if (totalWeight <= 0) return destinations.get(0);

        int randomVal = new java.util.Random().nextInt(totalWeight);
        int cumulativeWeight = 0;
        for (SmppRoutingDestination dest : destinations) {
            cumulativeWeight += dest.getWeightPercent();
            if (randomVal < cumulativeWeight) {
                return dest;
            }
        }
        return destinations.get(0);
    }
}
