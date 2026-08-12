package com.messagingagent.controller;

import com.messagingagent.model.SmppClient;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.dto.SmppClientDto;
import com.messagingagent.dto.SmppSessionDto;
import com.messagingagent.smpp.SmppSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/smpp/clients")
@RequiredArgsConstructor
public class SmppClientController {

    private final SmppClientRepository repository;
    private final SmppSessionRegistry sessionRegistry;
    private final StringRedisTemplate redisTemplate;

    private void syncToRedis(SmppClient client) {
        String key = "config:client:" + client.getSystemId() + ":password";
        if (client.isActive()) {
            redisTemplate.opsForValue().set(key, client.getPassword());
        } else {
            redisTemplate.delete(key);
        }
    }

    private void removeFromRedis(String systemId) {
        redisTemplate.delete("config:client:" + systemId + ":password");
    }

    @GetMapping
    public List<SmppClientDto> getAll() {
        return repository.findAll().stream().map(client -> {
            List<SmppSessionDto> activeSessions = sessionRegistry.getSessionsBySystemId(client.getSystemId())
                    .stream().map(info -> {
                        String bindType = info.getSession().getConfiguration().getType().toString();
                        long uptime = Duration.between(info.getBoundAt(), Instant.now()).getSeconds();
                        return new SmppSessionDto(info.getSessionId(), bindType, uptime);
                    }).collect(Collectors.toList());
            return SmppClientDto.fromEntity(client, activeSessions);
        }).collect(Collectors.toList());
    }

    @PostMapping
    @Transactional
    public SmppClient create(@RequestBody SmppClient client) {
        SmppClient saved = repository.save(client);
        syncToRedis(saved);
        return saved;
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<SmppClient> update(@PathVariable Long id, @RequestBody SmppClient clientDetails) {
        return repository.findById(id).map(client -> {
            client.setName(clientDetails.getName());
            client.setSystemId(clientDetails.getSystemId());
            if (clientDetails.getPassword() != null && !clientDetails.getPassword().trim().isEmpty()) {
                client.setPassword(clientDetails.getPassword());
            }
            client.setActive(clientDetails.isActive());
            client.setPriority(clientDetails.getPriority() != null ? clientDetails.getPriority() : 2);
            SmppClient saved = repository.save(client);
            syncToRedis(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(client -> {
            repository.delete(client);
            removeFromRedis(client.getSystemId());
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{systemId}/disconnect")
    public ResponseEntity<?> disconnectClients(@PathVariable String systemId) {
        sessionRegistry.getSessionsBySystemId(systemId).forEach(info -> {
            try {
                info.getSession().destroy();
            } catch (Exception ignored) {}
        });
        return ResponseEntity.ok().build();
    }
}
