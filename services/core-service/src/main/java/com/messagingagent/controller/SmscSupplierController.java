package com.messagingagent.controller;

import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.dto.SmscSupplierDto;
import com.messagingagent.dto.SmscSupplierRequestDto;
import com.messagingagent.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/smsc-suppliers")
@RequiredArgsConstructor
@Slf4j
public class SmscSupplierController {

    private final SmscSupplierRepository smscSupplierRepository;
    private final AccountRepository accountRepository;
    private final StringRedisTemplate redis;

    @GetMapping
    public List<SmscSupplierDto> getAllSuppliers() {
        return smscSupplierRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private SmscSupplierDto toDto(SmscSupplier supplier) {
        boolean connected = false;
        Long uptimeSeconds = null;

        try {
            String statusVal = redis.opsForValue().get("smsc:supplier:status:" + supplier.getId());
            if (statusVal != null && !statusVal.isBlank()) {
                connected = true;
                try {
                    java.time.Instant boundAt = java.time.Instant.parse(statusVal);
                    uptimeSeconds = java.time.Duration.between(boundAt, java.time.Instant.now()).getSeconds();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        return SmscSupplierDto.builder()
                .supplier(supplier)
                .accountId(supplier.getAccount() != null ? supplier.getAccount().getId() : null)
                .uptimeSeconds(uptimeSeconds)
                .connected(connected)
                .totalMessages(0L)
                .dlrsReceived(0L)
                .failed(0L)
                .inQueue(0L)
                .sentCount(supplier.getSentCount())
                .triggerResendErrorCodes(supplier.getTriggerResendErrorCodes())
                .build();
    }

    @PostMapping
    public ResponseEntity<SmscSupplierDto> createSupplier(@RequestBody SmscSupplierRequestDto dto) {
        log.info("Creating new SMSC supplier: {}", dto.getName());
        SmscSupplier supplier = new SmscSupplier();
        supplier.setName(dto.getName());
        supplier.setHost(dto.getHost());
        supplier.setPort(dto.getPort());
        supplier.setSystemId(dto.getSystemId());
        supplier.setPassword(dto.getPassword());
        supplier.setSystemType(dto.getSystemType());
        supplier.setBindType(dto.getBindType());
        supplier.setAddressRange(dto.getAddressRange());
        supplier.setSourceTon(dto.getSourceTon());
        supplier.setSourceNpi(dto.getSourceNpi());
        supplier.setDestTon(dto.getDestTon());
        supplier.setDestNpi(dto.getDestNpi());
        supplier.setThroughput(dto.getThroughput());
        supplier.setEnquireLinkInterval(dto.getEnquireLinkInterval());
        supplier.setActive(dto.isActive());
        supplier.setBypassDuplicateFilter(dto.isBypassDuplicateFilter());
        supplier.setTriggerResendErrorCodes(dto.getTriggerResendErrorCodes());
        
        if (dto.getAccountId() != null) {
            accountRepository.findById(dto.getAccountId()).ifPresent(supplier::setAccount);
        }

        SmscSupplier saved = smscSupplierRepository.save(supplier);
        return ResponseEntity.ok(toDto(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SmscSupplierDto> updateSupplier(@PathVariable Long id, @RequestBody SmscSupplierRequestDto dto) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        return smscSupplierRepository.findById(finalId).map(existing -> {
            log.info("Updating SMSC supplier: {}", finalId);
            existing.setName(dto.getName());
            existing.setHost(dto.getHost());
            existing.setPort(dto.getPort());
            existing.setSystemId(dto.getSystemId());
            if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
                existing.setPassword(dto.getPassword());
            }
            existing.setSystemType(dto.getSystemType());
            existing.setBindType(dto.getBindType());
            existing.setAddressRange(dto.getAddressRange());
            existing.setSourceTon(dto.getSourceTon());
            existing.setSourceNpi(dto.getSourceNpi());
            existing.setDestTon(dto.getDestTon());
            existing.setDestNpi(dto.getDestNpi());
            existing.setThroughput(dto.getThroughput());
            existing.setEnquireLinkInterval(dto.getEnquireLinkInterval());
            existing.setActive(dto.isActive());
            existing.setBypassDuplicateFilter(dto.isBypassDuplicateFilter());
            existing.setTriggerResendErrorCodes(dto.getTriggerResendErrorCodes());
            
            if (dto.getAccountId() != null) {
                accountRepository.findById(dto.getAccountId()).ifPresent(existing::setAccount);
            } else {
                existing.setAccount(null);
            }
            
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
