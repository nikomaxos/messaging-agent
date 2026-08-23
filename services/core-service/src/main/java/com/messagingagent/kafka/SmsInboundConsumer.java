package com.messagingagent.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messagingagent.model.MessageLog;
import com.messagingagent.model.SmppClient;
import com.messagingagent.model.SmppRouting;
import com.messagingagent.repository.MessageLogRepository;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.repository.SmppRoutingRepository;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.model.SmscSupplier;
import com.messagingagent.service.RoutingRuleService;
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
    private final SmscSupplierRepository smscSupplierRepository;
    private final MessageLogRepository messageLogRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RoutingRuleService routingRuleService;

    @KafkaListener(topics = "sms.inbound.raw", groupId = "core-service-raw-group")
    public void consumeRawInbound(String messageJson) {
        try {
            Map<String, Object> event = objectMapper.readValue(messageJson, Map.class);
            String systemId = (String) event.get("systemId");
            String correlationId = (String) event.get("correlationId");
            String sourceAddress = (String) event.get("sourceAddress");
            String destinationAddress = (String) event.get("destinationAddress");
            String messageText = (String) event.get("messageText");
            Integer dataCodingInt = (Integer) event.get("dataCoding");
            Byte dataCoding = dataCodingInt != null ? dataCodingInt.byteValue() : null;

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
                        .dataCoding(dataCoding)
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
            Integer dataCodingInt = (Integer) event.get("dataCoding");
            Byte dataCoding = dataCodingInt != null ? dataCodingInt.byteValue() : null;

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
                        .dataCoding(dataCoding)
                        .build();
                messageLogRepository.save(logEntry);
                return;
            }

            // --- Rules Engine Evaluation ---
            List<com.messagingagent.model.RoutingRule> rules = routingRuleService.getActiveRulesSorted();
            StringBuilder traceLogBuilder = new StringBuilder();
            boolean terminateRouting = false;

            for (com.messagingagent.model.RoutingRule rule : rules) {
                String ruleTrace = routingRuleService.evaluateRule(rule, event);
                if (ruleTrace != null) {
                    traceLogBuilder.append(ruleTrace).append(" | ");
                    if (event.containsKey("terminateRouting") && (Boolean) event.get("terminateRouting")) {
                        traceLogBuilder.append("Terminated. ");
                        terminateRouting = true;
                        break;
                    }
                }
            }

            // Refresh variables that might have been modified by rules
            final String finalSourceAddress = (String) event.get("sourceAddress");
            final String finalMessageText = (String) event.get("messageText");
            String traceData = traceLogBuilder.toString();

            Optional<SmppClient> clientOpt = smppClientRepository.findBySystemId(systemId);

            if (terminateRouting) {
                log.info("Message {} terminated by Rules Engine", correlationId);
                MessageLog logEntry = messageLogRepository.findBySmppMessageId(correlationId)
                        .orElseGet(() -> MessageLog.builder()
                                .smppMessageId(correlationId)
                                .customerMessageId(correlationId)
                                .smppClient(clientOpt.orElse(null))
                                .sourceAddress(finalSourceAddress)
                                .destinationAddress(destinationAddress)
                                .messageText(finalMessageText)
                                .isEmulated(false)
                                .dataCoding(dataCoding)
                                .build());

                logEntry.setSourceAddress(finalSourceAddress);
                logEntry.setMessageText(finalMessageText);
                logEntry.setTraceData(!traceData.isEmpty() ? traceData : null);

                if (event.containsKey("fakeDlrStatus")) {
                    String fakeStatus = (String) event.get("fakeDlrStatus");
                    logEntry.setStatus("DELIVRD".equalsIgnoreCase(fakeStatus) ? MessageLog.Status.DELIVERED : MessageLog.Status.FAILED);
                    logEntry.setErrorDetail("RULES_ENGINE_FAKE_DLR " + fakeStatus);
                    messageLogRepository.save(logEntry);

                    String dlrStatus = logEntry.getStatus() == MessageLog.Status.DELIVERED ? "DELIVERED" : "FAILED";
                    String dlrJson = String.format("{\"correlationId\":\"%s\", \"status\":\"%s\", \"reason\":\"%s\"}", 
                            correlationId, dlrStatus, fakeStatus);
                    kafkaTemplate.send("sms.delivery.receipt", correlationId, dlrJson);
                } else if (event.containsKey("dropMessage")) {
                    logEntry.setStatus(MessageLog.Status.FAILED);
                    logEntry.setErrorDetail("RULES_ENGINE_DROPPED");
                    messageLogRepository.save(logEntry);
                }
                return;
            }
            // --- End Rules Engine ---

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
                        .dataCoding(dataCoding)
                        .build();

                messageLogRepository.save(logEntry);
                
                // Send fake DLR back to smpp-edge
                String dlrStatus = (status == MessageLog.Status.DELIVERED) ? "DELIVERED" : "FAILED";
                String dlrJson = String.format("{\"correlationId\":\"%s\", \"status\":\"%s\", \"reason\":\"%s\"}", 
                        correlationId, dlrStatus, routing.getEmulatedErrorCode());
                kafkaTemplate.send("sms.delivery.receipt", correlationId, dlrJson);
                
                return;
            }

            // Check for forced Supplier override
            Number forcedSupplierId = (Number) event.get("forcedSupplierId");
            if (forcedSupplierId != null) {
                SmscSupplier forcedSupplier = smscSupplierRepository.findById(forcedSupplierId.longValue()).orElse(null);
                if (forcedSupplier != null) {
                    log.info("Bypassing routing for forced supplierId={}", forcedSupplierId);
                    MessageLog logEntry = messageLogRepository.findBySmppMessageId(correlationId)
                            .orElseGet(() -> MessageLog.builder()
                                    .smppMessageId(correlationId)
                                    .customerMessageId(correlationId)
                                    .smppClient(clientOpt.orElse(null))
                                    .sourceAddress(finalSourceAddress)
                                    .destinationAddress(destinationAddress)
                                    .messageText(finalMessageText)
                                    .isEmulated(false)
                                    .dataCoding(dataCoding)
                                    .build());

                    logEntry.setSourceAddress(finalSourceAddress);
                    logEntry.setMessageText(finalMessageText);
                    logEntry.setTraceData(!traceData.isEmpty() ? traceData : null);

                    logEntry.setStatus(MessageLog.Status.QUEUED);
                    if (logEntry.getSmppClient() == null && clientOpt.isPresent()) {
                        logEntry.setSmppClient(clientOpt.get());
                    }
                    
                    logEntry.setRoutingMode(com.messagingagent.model.RoutingMode.SMS);
                    logEntry.setFallbackSmsc(forcedSupplier);
                    
                    messageLogRepository.save(logEntry);
                    log.info("Saved message correlationId={} as QUEUED for dispatch", correlationId);

                    java.util.Map<String, Object> outboundEvent = new java.util.HashMap<>();
                    outboundEvent.put("correlationId", correlationId);
                    outboundEvent.put("supplierId", forcedSupplier.getId());
                    outboundEvent.put("sourceAddress", finalSourceAddress);
                    outboundEvent.put("destinationAddress", destinationAddress);
                    outboundEvent.put("messageText", finalMessageText);
                    if (logEntry.getDataCoding() != null) {
                        outboundEvent.put("dataCoding", logEntry.getDataCoding());
                    }
                    
                    kafkaTemplate.send("outbound.smpp", correlationId, objectMapper.writeValueAsString(outboundEvent));
                    log.info("Dispatched to outbound.smpp for correlationId={} supplierId={}", correlationId, forcedSupplier.getId());
                    return;
                }
            }

            // Normal routing
            MessageLog logEntry = messageLogRepository.findBySmppMessageId(correlationId)
                    .orElseGet(() -> MessageLog.builder()
                            .smppMessageId(correlationId)
                            .customerMessageId(correlationId)
                            .smppClient(clientOpt.orElse(null))
                            .sourceAddress(finalSourceAddress)
                            .destinationAddress(destinationAddress)
                            .messageText(finalMessageText)
                            .isEmulated(false)
                            .dataCoding(dataCoding)
                            .build());

            logEntry.setSourceAddress(finalSourceAddress);
            logEntry.setMessageText(finalMessageText);
            logEntry.setTraceData(!traceData.isEmpty() ? traceData : null);

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
                java.util.Map<String, Object> outboundEvent = new java.util.HashMap<>();
                outboundEvent.put("correlationId", correlationId);
                outboundEvent.put("supplierId", logEntry.getFallbackSmsc().getId());
                outboundEvent.put("sourceAddress", finalSourceAddress);
                outboundEvent.put("destinationAddress", destinationAddress);
                outboundEvent.put("messageText", finalMessageText);
                if (logEntry.getDataCoding() != null) {
                    outboundEvent.put("dataCoding", logEntry.getDataCoding());
                }
                
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
