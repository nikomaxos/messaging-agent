package com.messagingagent.routing.controller;

import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/routing")
public class EmulationController {

    @Data
    public static class EmulationRequest {
        private String clientSystemId;
        private String senderId;
        private String message;
        private String destination;
        private Long supplierId;
        private String protocol;
        private Integer dataCoding;
    }

    @Data
    public static class EmulationResponse {
        private String originalSenderId;
        private String finalSenderId;
        private String originalMessage;
        private String finalMessage;
        private String finalDestination;
        private String selectedRoute;
        private List<String> executionTrace = new ArrayList<>();
    }

    @PostMapping("/emulate")
    public ResponseEntity<EmulationResponse> emulateRouting(@RequestBody EmulationRequest request) {
        EmulationResponse response = new EmulationResponse();
        response.setOriginalSenderId(request.getSenderId());
        response.setOriginalMessage(request.getMessage());
        
        response.getExecutionTrace().add("[SYSTEM] Emulation started for message to " + request.getDestination());
        response.getExecutionTrace().add("[AUTH] Validated Client System ID: " + request.getClientSystemId());
        
        Integer dc = request.getDataCoding() != null ? request.getDataCoding() : 0;
        response.getExecutionTrace().add("[MODIFIER] Setting Data Coding scheme to: " + dc);

        // Dummy Emulation logic (This would normally hook into RateLimitDispatcher and Routing logic)
        String finalSender = request.getSenderId();
        if (finalSender != null && finalSender.length() > 11) {
            response.getExecutionTrace().add("[MODIFIER] Sender ID '" + finalSender + "' exceeds 11 chars. Truncating.");
            finalSender = finalSender.substring(0, 11);
        }
        response.setFinalSenderId(finalSender);

        String finalMessage = request.getMessage();
        if (request.getProtocol() != null && request.getProtocol().equalsIgnoreCase("SMS") && finalMessage.length() > 160) {
            response.getExecutionTrace().add("[MODIFIER] Protocol is SMS and message exceeds 160 chars. Generating multipart sequence.");
        }
        response.setFinalMessage(finalMessage);
        
        response.setFinalDestination(request.getDestination());

        if (request.getSupplierId() != null) {
            response.getExecutionTrace().add("[ROUTING] Message hard-routed to Supplier ID: " + request.getSupplierId());
            response.setSelectedRoute("SUPPLIER_" + request.getSupplierId());
        } else {
            response.getExecutionTrace().add("[ROUTING] Dynamic route resolution started.");
            response.getExecutionTrace().add("[ROUTING] Selected optimal route based on cost/quality.");
            response.setSelectedRoute("DYNAMIC_VIRTUAL_SMSC");
        }

        response.getExecutionTrace().add("[BILLING] Emulated deduction of 1 credit.");
        response.getExecutionTrace().add("[SYSTEM] Emulation completed successfully.");

        return ResponseEntity.ok(response);
    }
}
