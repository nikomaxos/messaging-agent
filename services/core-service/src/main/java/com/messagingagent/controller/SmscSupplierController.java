package com.messagingagent.controller;

import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.dto.SmscSupplierDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/smsc-suppliers")
@RequiredArgsConstructor
@Slf4j
public class SmscSupplierController {

    private final SmscSupplierRepository smscSupplierRepository;

    @GetMapping
    public List<SmscSupplierDto> getAllSuppliers() {
        return smscSupplierRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private SmscSupplierDto toDto(SmscSupplier supplier) {
        return SmscSupplierDto.builder()
                .supplier(supplier)
                .uptimeSeconds(null)
                .connected(false)
                .totalMessages(0L)
                .dlrsReceived(0L)
                .failed(0L)
                .inQueue(0L)
                .build();
    }

    @PostMapping
    public ResponseEntity<SmscSupplierDto> createSupplier(@RequestBody SmscSupplier supplier) {
        log.info("Creating new SMSC supplier: {}", supplier.getName());
        SmscSupplier saved = smscSupplierRepository.save(supplier);
        return ResponseEntity.ok(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SmscSupplierDto> updateSupplier(@PathVariable Long id, @RequestBody SmscSupplier supplier) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        return smscSupplierRepository.findById(finalId).map(existing -> {
            log.info("Updating SMSC supplier: {}", finalId);
            existing.setName(supplier.getName());
            existing.setHost(supplier.getHost());
            existing.setPort(supplier.getPort());
            existing.setSystemId(supplier.getSystemId());
            if (supplier.getPassword() != null && !supplier.getPassword().isBlank()) {
                existing.setPassword(supplier.getPassword());
            }
            existing.setSystemType(supplier.getSystemType());
            existing.setBindType(supplier.getBindType());
            existing.setAddressRange(supplier.getAddressRange());
            existing.setSourceTon(supplier.getSourceTon());
            existing.setSourceNpi(supplier.getSourceNpi());
            existing.setDestTon(supplier.getDestTon());
            existing.setDestNpi(supplier.getDestNpi());
            existing.setThroughput(supplier.getThroughput());
            existing.setEnquireLinkInterval(supplier.getEnquireLinkInterval());
            existing.setActive(supplier.isActive());
            
            SmscSupplier saved = smscSupplierRepository.save(existing);
            return ResponseEntity.ok(toDto(saved));
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSupplier(@PathVariable Long id) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        log.info("Deleting SMSC supplier: {}", finalId);
        if (!smscSupplierRepository.existsById(finalId)) {
            return ResponseEntity.notFound().build();
        }
        smscSupplierRepository.deleteById(finalId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bind")
    public ResponseEntity<Void> bindSupplier(@PathVariable Long id) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        return smscSupplierRepository.findById(finalId).map(supplier -> {
            supplier.setActive(true);
            smscSupplierRepository.save(supplier);
            return ResponseEntity.ok().<Void>build();
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    @PostMapping("/{id}/unbind")
    public ResponseEntity<Void> unbindSupplier(@PathVariable Long id) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        return smscSupplierRepository.findById(finalId).map(supplier -> {
            supplier.setActive(false);
            smscSupplierRepository.save(supplier);
            return ResponseEntity.ok().<Void>build();
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }
}
