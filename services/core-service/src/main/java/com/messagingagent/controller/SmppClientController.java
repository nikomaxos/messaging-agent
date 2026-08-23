package com.messagingagent.controller;

import com.messagingagent.model.SmppClient;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.dto.SmppClientDto;
import com.messagingagent.dto.SmppSessionDto;
import com.messagingagent.service.RedisConfigSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/smpp/clients")
@RequiredArgsConstructor
public class SmppClientController {

    private final SmppClientRepository repository;
    private final com.messagingagent.repository.UsernameRepository usernameRepository;
    private final RedisConfigSyncService syncService;
    private final StringRedisTemplate redis;

    @GetMapping
    public List<SmppClientDto> getAll() {
        return repository.findAll().stream().map(client -> {
            String key = "smpp:sessions:" + client.getSystemId();
            Map<Object, Object> sessionsMap = redis.opsForHash().entries(key);
            List<SmppSessionDto> activeSessions = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : sessionsMap.entrySet()) {
                String sessionId = (String) entry.getKey();
                String value = (String) entry.getValue();
                String[] parts = value.split("\\|");
                String bindType = parts.length > 0 ? parts[0] : "UNKNOWN";
                long uptime = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                activeSessions.add(new SmppSessionDto(sessionId, bindType, uptime));
            }
            return SmppClientDto.fromEntity(client, activeSessions);
        }).collect(Collectors.toList());
    }

    @PostMapping
    @Transactional
    public SmppClient create(@RequestBody SmppClientDto clientDetails) {
        SmppClient client = new SmppClient();
        client.setName(clientDetails.getName());
        client.setSystemId(clientDetails.getSystemId());
        client.setPassword(clientDetails.getPassword());
        client.setActive(clientDetails.isActive());
        if (clientDetails.getUsernameId() != null) {
            usernameRepository.findById(clientDetails.getUsernameId()).ifPresent(client::setUsername);
        }
        SmppClient saved = repository.save(client);
        syncService.syncClient(saved);
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<SmppClient> update(@PathVariable Long id, @RequestBody SmppClientDto clientDetails) {
        return repository.findById(id).map(client -> {
            client.setName(clientDetails.getName());
            client.setSystemId(clientDetails.getSystemId());
            if (clientDetails.getPassword() != null && !clientDetails.getPassword().trim().isEmpty()) {
                client.setPassword(clientDetails.getPassword());
            }
            client.setActive(clientDetails.isActive());
            if (clientDetails.getUsernameId() != null) {
                usernameRepository.findById(clientDetails.getUsernameId()).ifPresent(client::setUsername);
            } else {
                client.setUsername(null);
            }
            SmppClient saved = repository.save(client);
            syncService.syncClient(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(client -> {
            repository.delete(client);
            syncService.deleteClient(client.getSystemId());
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{systemId}/disconnect")
    public ResponseEntity<?> disconnectClients(@PathVariable String systemId) {
        // Disconnect logic will be handled via Kafka event later
        return ResponseEntity.ok().build();
    }
}
