package com.messagingagent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import com.messagingagent.model.Device;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixRouteService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;

    @Value("${matrix.synapse.url:http://ma-synapse:8008}")
    private String synapseUrl;

    @Value("${matrix.appservice.token:pRsqHyaFEqmFDeQy6yHGynoeegtTBrwGsUcdL93f09Tx6FMcc7o1QJue5lVvUuYT}")
    private String asToken;

    @Value("${matrix.domain:synapse}")
    private String matrixDomain;

    // Rate limiting is now delegated to MatrixQueueService which allows decoupling of HTTP threads

    private final Map<Long, String> realTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> portalCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, String> botRoomCache = new java.util.concurrent.ConcurrentHashMap<>();

    public String getRealToken(Device device) {
        if (realTokens.containsKey(device.getId())) {
            return realTokens.get(device.getId());
        }
        String username = "device_" + device.getId();
        String password = "msgagent-device-" + device.getId();
        
        String url = synapseUrl + "/_matrix/client/v3/login";
        Map<String, String> body = Map.of(
            "type", "m.login.password",
            "user", username,
            "password", password
        );
        try {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response != null && response.containsKey("access_token")) {
                String token = (String) response.get("access_token");
                realTokens.put(device.getId(), token);
                return token;
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                log.info("User {} forbidden/not found, attempting auto-registration...", username);
                try {
                    String regUrl = synapseUrl + "/_matrix/client/v3/register";
                    Map<String, Object> regBody = Map.of(
                        "username", username,
                        "password", password,
                        "auth", Map.of("type", "m.login.dummy")
                    );
                    Map<String, Object> regResp = restTemplate.postForObject(regUrl, regBody, Map.class);
                    if (regResp != null && regResp.containsKey("access_token")) {
                        String token = (String) regResp.get("access_token");
                        realTokens.put(device.getId(), token);
                        return token;
                    }
                } catch (Exception ex) {
                    log.error("Auto-registration failed for {}: {}", username, ex.getMessage());
                }
            } else if (e.getStatusCode().value() == 429) {
                log.warn("Login rate limited for {}: {}", username, e.getResponseBodyAsString());
            } else {
                log.error("Failed to login real user {}: {}", username, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to login real user {}: {}", username, e.getMessage());
        }
        return asToken;
    }

    public String sendMessage(Device device, String destinationAddress, String text) {
        if (destinationAddress == null || destinationAddress.trim().isEmpty()) {
            log.warn("Cannot send Matrix message with empty destination address for device {}", device.getId());
            return null;
        }
        try {
            String deviceUserId = String.format("@device_%d:%s", device.getId(), matrixDomain);
            String digitsOnly = destinationAddress.replaceAll("[^\\d]", "");
            if (digitsOnly.isEmpty()) {
                log.warn("Cannot send Matrix message: destination address contains no digits '{}'", destinationAddress);
                return null;
            }
            String formattedAddress = "+" + digitsOnly;
            String cacheKey = device.getId() + "_" + formattedAddress;
            String roomId = portalCache.get(cacheKey);

            String token = getRealToken(device);

            if (roomId == null) {
                // Determine Portal Room using the bot
                String botUserId = "@gmessagesbot:" + matrixDomain;
                String botRoomId = botRoomCache.get(device.getId());
                
                if (botRoomId == null) {
                    botRoomId = createDirectRoom(deviceUserId, botUserId, token);
                    if (botRoomId != null) {
                        botRoomCache.put(device.getId(), botRoomId);
                        try { Thread.sleep(2000); } catch (Exception ignored) {} // wait for bot to join
                    }
                }
                
                if (botRoomId == null) {
                    log.error("Failed to create management room to {}", botUserId);
                    return null;
                }

                // Ask the bot for the portal
                sendRoomMessage(deviceUserId, botRoomId, token, "!gm pm " + formattedAddress);
                
                // Allow the bridge to respond and create the room
                int attempts = 0;
                while (attempts < 8) {
                    Thread.sleep(1000);
                    roomId = extractPortalRoomId(deviceUserId, botRoomId, token);
                    if (roomId != null) {
                        break;
                    }
                    attempts++;
                }

                if (roomId != null) {
                    joinRoom(deviceUserId, roomId, token);
                    portalCache.put(cacheKey, roomId);
                } else {
                    log.error("Failed to extract portal room ID from Mautrix bridge bot");

                    return null;
                }
            }
            
            // Send the actual message payload to the bridge's created portal!
            return sendRoomMessage(deviceUserId, roomId, token, text);
        } catch (Exception e) {
            log.error("Error sending message via Matrix for destination {}: {}", destinationAddress, e.getMessage(), e);
            return null;
        }
    }

    private String createDirectRoom(String deviceUserId, String targetUserId, String token) {
        String url = synapseUrl + "/_matrix/client/v3/createRoom?user_id=" + deviceUserId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "invite", new String[]{targetUserId},
            "is_direct", true,
            "preset", "trusted_private_chat"
        );

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("room_id")) {
                return (String) response.get("room_id");
            }
        } catch (Exception e) {
            log.error("Matrix API /createRoom failed for {}: {}", targetUserId, e.getMessage());
        }
        return null;
    }

    private void joinRoom(String deviceUserId, String roomId, String token) {
        String url = synapseUrl + "/_matrix/client/v3/join/{roomId}?user_id={userId}";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(), headers);
            restTemplate.postForObject(url, request, Map.class, roomId, deviceUserId);
            log.info("Successfully joined portal room {} for user {}", roomId, deviceUserId);
        } catch (Exception e) {
            log.warn("Matrix API /join failed for room {}: {}", roomId, e.getMessage());
        }
    }

    private String sendRoomMessage(String deviceUserId, String roomId, String token, String payload) {

        String txnId = UUID.randomUUID().toString();
        String url = synapseUrl + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId + "?user_id=" + deviceUserId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "msgtype", "m.text",
            "body", payload
        );

        int attempts = 0;
        while (attempts < 3) {
            try {
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.PUT, request, Map.class).getBody();
                if (response != null && response.containsKey("event_id")) {
                    return (String) response.get("event_id");
                }
                return null;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                attempts++;
                if (attempts >= 3) {
                    log.error("Matrix API /send rate limited permanently for room {}: {}", roomId, e.getMessage());
                    return null;
                }
                try {
                    long waitMs = 3000;
                    String respBody = e.getResponseBodyAsString();
                    if (respBody != null && respBody.contains("retry_after_ms")) {
                        com.fasterxml.jackson.databind.JsonNode errNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(respBody);
                        if (errNode.has("retry_after_ms")) {
                            waitMs = errNode.get("retry_after_ms").asLong() + 200;
                        }
                    }
                    log.warn("Rate limited by Synapse, retrying in {} ms...", waitMs);
                    Thread.sleep(waitMs);
                } catch (Exception ex) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            } catch (Exception e) {
                log.error("Matrix API /send/m.room.message failed for room {}: {}", roomId, e.getMessage());
                return null;
            }
        }
        return null;
    }

    private String extractPortalRoomId(String deviceUserId, String botRoomId, String token) {
        String url = synapseUrl + "/_matrix/client/v3/rooms/" + botRoomId + "/messages?dir=b&limit=5&user_id=" + deviceUserId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        try {
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, request, Map.class).getBody();
            if (response != null && response.containsKey("chunk")) {
                java.util.List<Map<String, Object>> chunk = (java.util.List<Map<String, Object>>) response.get("chunk");
                for (Map<String, Object> event : chunk) {
                    if (event.containsKey("sender") && event.get("sender").toString().startsWith("@gmessagesbot")) {
                        Map<String, Object> content = (Map<String, Object>) event.get("content");
                        if (content != null && content.containsKey("body")) {
                            String body = (String) content.get("body");
                            if (body.contains("direct chat with") || body.contains("Created Google Messages chat")) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile("!([a-zA-Z0-9_-]+):" + matrixDomain).matcher(body);
                                if (m.find()) {
                                    return m.group(0);
                                }
                            } else {
                                log.warn("Mautrix bridge bot responded with unexpected message: '{}'", body);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
             log.error("Failed to extract bot message from room {}", botRoomId, e);
        }
        return null;
    }

    public String sendSystemMessage(String roomId, String text) {
        String txnId = UUID.randomUUID().toString();
        String url = synapseUrl + "/_matrix/client/v3/rooms/" + roomId + "/send/m.room.message/" + txnId + "?user_id=@gmessagesbot:" + matrixDomain;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(asToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "msgtype", "m.text",
            "body", text
        );

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(url, org.springframework.http.HttpMethod.PUT, request, Map.class).getBody();
            if (response != null && response.containsKey("event_id")) {
                return (String) response.get("event_id");
            }
        } catch (Exception e) {
            log.error("Matrix API system message failed: {}", e.getMessage());
        }
        return null;
    }
}
