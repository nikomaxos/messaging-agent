package com.messagingagent.controller;

import com.messagingagent.model.SmscSupplier;
import com.messagingagent.repository.SmscSupplierRepository;
import com.messagingagent.smpp.SmscConnectionManager;
import com.messagingagent.dto.SmscSupplierDto;
import com.messagingagent.model.MessageLog;
import com.messagingagent.repository.MessageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/smsc-suppliers")
@RequiredArgsConstructor
@Slf4j
public class SmscSupplierController {

    private final SmscSupplierRepository smscSupplierRepository;
    private final SmscConnectionManager connectionManager;
    private final MessageLogRepository messageLogRepository;

    @GetMapping
    public List<SmscSupplierDto> getAllSuppliers() {
        return smscSupplierRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    private SmscSupplierDto toDto(SmscSupplier supplier) {
        @SuppressWarnings("null")
        @lombok.NonNull Long supplierId = supplier.getId();
        SmscConnectionManager.UpstreamSessionInfo info = connectionManager.getSessionInfo(supplierId);
        
        Long uptimeSeconds = null;
        boolean connected = false;
        if (info != null && info.session() != null && info.session().isBound()) {
            connected = true;
            if (info.boundAt() != null) {
                uptimeSeconds = ChronoUnit.SECONDS.between(info.boundAt(), Instant.now());
            }
        }

        Instant startOfMonth = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant();

        long total = messageLogRepository.countTotalBySmscSince(supplierId, startOfMonth);
        long dlrs = messageLogRepository.countBySmscAndStatusesSince(supplierId, List.of(MessageLog.Status.DELIVERED), startOfMonth);
        long failed = messageLogRepository.countBySmscAndStatusesSince(supplierId, List.of(MessageLog.Status.FAILED, MessageLog.Status.RCS_FAILED), startOfMonth);
        long queued = messageLogRepository.countBySmscAndStatusesSince(supplierId, List.of(MessageLog.Status.RECEIVED, MessageLog.Status.DISPATCHED), startOfMonth);

        return SmscSupplierDto.builder()
                .supplier(supplier)
                .uptimeSeconds(uptimeSeconds)
                .connected(connected)
                .totalMessages(total)
                .dlrsReceived(dlrs)
                .failed(failed)
                .inQueue(queued)
                .build();
    }

    @PostMapping
    public ResponseEntity<SmscSupplierDto> createSupplier(@RequestBody SmscSupplier supplier) {
        log.info("Creating new SMSC supplier: {}", supplier.getName());
        SmscSupplier saved = smscSupplierRepository.save(supplier);
        connectionManager.reload();
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
            connectionManager.reload();
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
        connectionManager.reload();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/bind")
    public ResponseEntity<Void> bindSupplier(@PathVariable Long id) {
        @SuppressWarnings("null")
        @lombok.NonNull Long finalId = id;
        return smscSupplierRepository.findById(finalId).map(supplier -> {
            supplier.setActive(true);
            smscSupplierRepository.save(supplier);
            connectionManager.bindSupplier(supplier);
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
            connectionManager.unbindSupplier(finalId);
            return ResponseEntity.ok().<Void>build();
        }).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }
}
