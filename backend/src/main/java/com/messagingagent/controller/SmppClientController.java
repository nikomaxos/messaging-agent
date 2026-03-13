package com.messagingagent.controller;

import com.messagingagent.model.SmppClient;
import com.messagingagent.repository.SmppClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/smpp/clients")
@RequiredArgsConstructor
public class SmppClientController {

    private final SmppClientRepository repository;

    @GetMapping
    public List<SmppClient> getAll() {
        return repository.findAll();
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
}
