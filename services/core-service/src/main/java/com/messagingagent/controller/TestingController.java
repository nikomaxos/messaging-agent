package com.messagingagent.controller;

import com.messagingagent.model.CountryPrefix;
import com.messagingagent.repository.CountryPrefixRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/testing")
@RequiredArgsConstructor
public class TestingController {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CountryPrefixRepository prefixRepository;
    private final Random random = new Random();

    @Data
    public static class SingleTestRequest {
        private String senderId;
        private String message;
        private String destination;
        private Long supplierId;
        private String protocol; // SMS, RCS, WHATSAPP, WEBSOCKET
        private Integer dataCoding;
        private Boolean isFlash;
    }

    @PostMapping("/send")
    public ResponseEntity<?> sendSingleTest(@RequestBody SingleTestRequest request) {
        // Send a single live test message
        int dc = request.getDataCoding() != null ? request.getDataCoding() : 0;
        if (request.getIsFlash() != null && request.getIsFlash()) {
            dc = dc | 0x10; // Set Message Class 0 (Flash SMS) bit
        }
        
        Map<String, Object> event = new HashMap<>();
        event.put("systemId", "TEST_CLIENT");
        event.put("sourceAddress", request.getSenderId());
        event.put("destinationAddress", request.getDestination());
        event.put("messageText", request.getMessage());
        event.put("dataCoding", dc);
        event.put("correlationId", "TEST-" + UUID.randomUUID().toString());
        event.put("timestampMs", System.currentTimeMillis());
        event.put("priority", 2);

        kafkaTemplate.send("sms.inbound.raw", event);
        return ResponseEntity.ok().body("{\"status\":\"Sent to Kafka\"}");
    }

    @Data
    public static class StressTestRequest {
        private String senderId;
        private String message;
        private String clientSystemId;
        private Long supplierId;
        private String protocol;
        private int amount;
        private String countryName;
        private String simulationMode; // "SIMULATE_DELIVERY" or "ACTUAL_SEND"
        private String forcedErrorCode; // e.g., "DELIVRD", "UNDELIV", "REJECTD"
        private Integer dataCoding;
        private String specificNumbers;
    }

    @PostMapping("/stress")
    public ResponseEntity<?> sendStressTest(@RequestBody StressTestRequest request) {
        int baseDc = request.getDataCoding() != null ? request.getDataCoding() : 0;

        if (request.getSpecificNumbers() != null && !request.getSpecificNumbers().trim().isEmpty()) {
            String[] numbers = request.getSpecificNumbers().split(",");
            int count = 0;
            for (String number : numbers) {
                String dest = number.trim();
                if (dest.isEmpty()) continue;
                
                Map<String, Object> event = new HashMap<>();
                event.put("systemId", request.getClientSystemId() != null && !request.getClientSystemId().isEmpty() ? request.getClientSystemId() : "STRESS_TEST");
                event.put("sourceAddress", request.getSenderId());
                event.put("destinationAddress", dest);
                event.put("messageText", request.getMessage());
                event.put("dataCoding", baseDc);
                event.put("correlationId", "TEST-STRESS-" + UUID.randomUUID().toString());
                event.put("timestampMs", System.currentTimeMillis());
                event.put("priority", 2);
                
                kafkaTemplate.send("sms.inbound.raw", event);
                count++;
            }
            return ResponseEntity.ok().body("{\"status\":\"Generated " + count + " specific messages\"}");
        }

        if (request.getAmount() <= 0 || request.getAmount() > 100000) {
            return ResponseEntity.badRequest().body("{\"error\":\"Invalid amount. Max 100,000\"}");
        }

        List<CountryPrefix> prefixes = prefixRepository.findByCountryNameIgnoreCase(request.getCountryName());
        if (prefixes.isEmpty()) {
            return ResponseEntity.badRequest().body("{\"error\":\"No active prefixes found for country: " + request.getCountryName() + "\"}");
        }

        boolean simulateDelivery = "SIMULATE_DELIVERY".equals(request.getSimulationMode());



        for (int i = 0; i < request.getAmount(); i++) {
            CountryPrefix randomPrefix = prefixes.get(random.nextInt(prefixes.size()));
            String dummyNumber = randomPrefix.getPrefix() + generateRandomDigits(10 - randomPrefix.getPrefix().length());
            
            Map<String, Object> event = new HashMap<>();
            event.put("systemId", request.getClientSystemId() != null && !request.getClientSystemId().isEmpty() ? request.getClientSystemId() : "STRESS_TEST");
            event.put("sourceAddress", request.getSenderId());
            event.put("destinationAddress", dummyNumber);
            event.put("messageText", request.getMessage());
            event.put("dataCoding", baseDc);
            event.put("correlationId", "TEST-STRESS-" + UUID.randomUUID().toString());
            event.put("timestampMs", System.currentTimeMillis());
            event.put("priority", 2);
            
            kafkaTemplate.send("sms.inbound.raw", event);
        }

        return ResponseEntity.ok().body("{\"status\":\"Generated " + request.getAmount() + " messages\"}");
    }

    private String generateRandomDigits(int length) {
        if (length <= 0) return "123456";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();

    }
}
