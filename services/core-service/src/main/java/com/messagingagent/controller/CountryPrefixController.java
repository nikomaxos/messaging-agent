package com.messagingagent.controller;

import com.messagingagent.model.CountryPrefix;
import com.messagingagent.repository.CountryPrefixRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routing/prefixes")
@RequiredArgsConstructor
public class CountryPrefixController {

    private final CountryPrefixRepository repository;

    @GetMapping
    public List<CountryPrefix> getAll() {
        return repository.findAll();
    }
    
    @GetMapping("/active")
    public List<CountryPrefix> getActive() {
        return repository.findByActiveTrue();
    }

    @PostMapping
    @Transactional
    public CountryPrefix create(@RequestBody CountryPrefix prefix) {
        return repository.save(prefix);
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<CountryPrefix> update(@PathVariable Long id, @RequestBody CountryPrefix details) {
        return repository.findById(id).map(prefix -> {
            prefix.setCountryName(details.getCountryName());
            prefix.setPrefix(details.getPrefix());
            prefix.setNetworkName(details.getNetworkName());
            prefix.setMcc(details.getMcc());
            prefix.setMnc(details.getMnc());
            prefix.setIso(details.getIso());
            prefix.setActive(details.isActive());
            return ResponseEntity.ok(repository.save(prefix));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return repository.findById(id).map(prefix -> {
            repository.delete(prefix);
            return ResponseEntity.ok().build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<?> bulkCreateOrUpdate(@RequestBody List<CountryPrefix> prefixes) {
        // Simply delete all and insert to refresh. Or just saveAll.
        // For simplicity of a full refresh:
        repository.deleteAll();
        List<CountryPrefix> saved = repository.saveAll(prefixes);
        return ResponseEntity.ok(saved.size());
    }
}
