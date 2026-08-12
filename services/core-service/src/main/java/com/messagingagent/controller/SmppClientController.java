package com.messagingagent.controller;

import com.messagingagent.model.SmppClient;
import com.messagingagent.repository.SmppClientRepository;
import com.messagingagent.dto.SmppClientDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/smpp/clients")
@RequiredArgsConstructor
public class SmppClientController {

    private final SmppClientRepository repository;

    @GetMapping
    public List<SmppClientDto> getAll() {
        return repository.findAll().stream().map(client -> {
            return SmppClientDto.fromEntity(client, List.of());
        }).collect(Collectors.toList());
    }

    @PostMapping
    @Transactional
    public SmppClient create(@RequestBody SmppClient client) {
        return repository.save(client);
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
            return ResponseEntity.ok(repository.save(client));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(client -> {
            repository.delete(client);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{systemId}/disconnect")
    public ResponseEntity<?> disconnectClients(@PathVariable String systemId) {
        // Disconnect logic will be handled via Kafka event later
        return ResponseEntity.ok().build();
    }
}
