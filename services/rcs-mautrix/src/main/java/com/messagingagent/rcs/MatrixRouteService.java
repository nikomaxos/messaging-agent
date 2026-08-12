package com.messagingagent.rcs;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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

    @Value("${spring.datasource.password:msgagent}")
    private String dbPassword;

    // Rate limiting is now delegated to MatrixQueueService which allows decoupling of HTTP threads

    private final Map<Long, String> realTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> portalCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, String> botRoomCache = new java.util.concurrent.ConcurrentHashMap<>();

    private String resolvePortalRoomIdDirect(String phoneNumber, String matrixUserId) {
        String url = "jdbc:postgresql://ma-postgres:5432/mautrix";
        String user = "msgagent";
        String query = "SELECT p.mxid FROM portal p " +
                       "JOIN ghost g ON p.other_user_id = g.id " +
                       "JOIN user_portal up ON (p.id = up.portal_id AND p.receiver = up.portal_receiver) " +
                       "WHERE REPLACE(g.name, ' ', '') LIKE ? " +
                       "AND up.user_mxid = ? " +
                       "AND p.mxid IS NOT NULL AND p.mxid != '' LIMIT 1";
        
        String searchNumber = phoneNumber.length() > 10 ? phoneNumber.substring(phoneNumber.length() - 10) : phoneNumber;
        
        try (Connection conn = DriverManager.getConnection(url, user, dbPassword);
             PreparedStatement stmt = conn.prepareStatement(query)) {
             
            stmt.setString(1, "%" + searchNumber + "%");
            stmt.setString(2, matrixUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("mxid");
                }
            }
        } catch (Exception e) {
            log.error("Failed to query mautrix database for portal resolution: {}", e.getMessage());
        }
        return null;
    }

    public String getRealToken(Long deviceId, String matrixId) {
        if (realTokens.containsKey(deviceId)) {
            return realTokens.get(deviceId);
        }
        String username = matrixId != null && !matrixId.isEmpty() ? matrixId : "device_" + deviceId;
        String password = "msgagent-" + username.replace("_", "-");
        
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
                realTokens.put(deviceId, token);
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
                        realTokens.put(deviceId, token);
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

    public String sendMessage(Long deviceId, String matrixId, String destinationAddress, String text) {
        if (destinationAddress == null || destinationAddress.trim().isEmpty()) {
            log.warn("Cannot send Matrix message with empty destination address for device {}", deviceId);
            return null;
        }
        try {
            String username = matrixId != null && !matrixId.isEmpty() ? matrixId : "device_" + deviceId;
            String deviceUserId = String.format("@%s:%s", username, matrixDomain);
            String digitsOnly = destinationAddress.replaceAll("[^\\d]", "");
            if (digitsOnly.isEmpty()) {
                log.warn("Cannot send Matrix message: destination address contains no digits '{}'", destinationAddress);
                return null;
            }
            String formattedAddress = "+" + digitsOnly;
            String cacheKey = deviceId + "_" + formattedAddress;
            String roomId = portalCache.get(cacheKey);

            String token = getRealToken(deviceId, matrixId);

            if (roomId == null) {
                // Try direct DB resolution first
                roomId = resolvePortalRoomIdDirect(digitsOnly, deviceUserId);

                if (roomId == null) {
                    // Determine Portal Room using the bot
                    String botUserId = "@gmessagesbot:" + matrixDomain;
                    String botRoomId = botRoomCache.get(deviceId);
                    
                    if (botRoomId == null) {
                        botRoomId = createDirectRoom(deviceUserId, botUserId, token);
                        if (botRoomId != null) {
                            botRoomCache.put(deviceId, botRoomId);
                            try { Thread.sleep(2000); } catch (Exception ignored) {} // wait for bot to join
                        }
                    }
                    
                    if (botRoomId == null) {
                        log.error("Failed to create management room to {}", botUserId);
                        return null;
                    }

                    // Ask the bot for the portal
                    sendRoomMessage(deviceUserId, botRoomId, token, "!gm pm " + formattedAddress);
                    
                    // Poll the database instead of reading bot chats
                    int attempts = 0;
                    while (attempts < 25) {
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                        roomId = resolvePortalRoomIdDirect(digitsOnly, deviceUserId);
                        if (roomId != null) {
                            log.info("Resolved portal room ID {} for new chat. Waiting 8s for Jibe RCS capabilities...", roomId);
                            try { Thread.sleep(8000); } catch (Exception ignored) {}
                            break;
                        }
                        attempts++;
                    }

                    if (roomId == null) {
                        log.error("Failed to resolve portal room ID from Mautrix DB after 25 seconds");
                        return null;
                    }
                }
                
                if (!joinRoom(deviceUserId, roomId, token)) {
                    log.error("Failed to join portal room {} after 5 attempts", roomId);
                    return null;
                }
                portalCache.put(cacheKey, roomId);
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

    private boolean joinRoom(String deviceUserId, String roomId, String token) {
        String url = synapseUrl + "/_matrix/client/v3/join/{roomId}?user_id={userId}";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        int attempts = 0;
        while (attempts < 5) {
            try {
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(), headers);
                restTemplate.postForObject(url, request, Map.class, roomId, deviceUserId);
                log.info("Successfully joined portal room {} for user {}", roomId, deviceUserId);
                return true;
            } catch (Exception e) {
                log.warn("Matrix API /join failed for room {} (attempt {}/5): {}", roomId, attempts + 1, e.getMessage());
                attempts++;
                try { Thread.sleep(1000); } catch (Exception ignored) {}
            }
        }
        return false;
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
