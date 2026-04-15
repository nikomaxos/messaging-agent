package com.messagingagent.admin;

import com.messagingagent.model.Device;
import com.messagingagent.model.SimCard;
import com.messagingagent.repository.DeviceRepository;
import com.messagingagent.repository.SimCardRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sim-cards")
@RequiredArgsConstructor
public class SimCardController {

    private final SimCardRepository simCardRepository;
    private final DeviceRepository deviceRepository;

    @GetMapping
    public List<SimCard> listAll() {
        return simCardRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SimCard> getById(@PathVariable Long id) {
        return simCardRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/assign")
    public ResponseEntity<SimCard> assignToDevice(@PathVariable Long id, @RequestBody AssignSimRequest req) {
        return simCardRepository.findById(id).map(sim -> {
            if (req.getDeviceId() != null) {
                Device d = deviceRepository.findById(req.getDeviceId()).orElse(null);
                sim.setDevice(d);
            } else {
                sim.setDevice(null);
            }
            if (req.getSlotIndex() != null) {
                sim.setSlotIndex(req.getSlotIndex());
            }
            return ResponseEntity.ok(simCardRepository.save(sim));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<SimCard> updateFields(@PathVariable Long id, @RequestBody EditSimRequest req) {
        return simCardRepository.findById(id).map(sim -> {
            sim.setPhoneNumber(req.getPhoneNumber() != null ? req.getPhoneNumber().trim() : null);
            sim.setCarrierName(req.getCarrierName() != null ? req.getCarrierName().trim() : null);
            if (req.getImsi() != null) sim.setImsi(req.getImsi().trim());
            if (req.getImei() != null) sim.setImei(req.getImei().trim());
            if (req.getSlotIndex() != null) sim.setSlotIndex(req.getSlotIndex());
            return ResponseEntity.ok(simCardRepository.save(sim));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<SimCard> createSim(@RequestBody EditSimRequest req) {
        SimCard sim = new SimCard();
        sim.setIccid(req.getIccid() != null && !req.getIccid().isBlank() ? req.getIccid().trim() : "MANUAL-" + System.currentTimeMillis());
        sim.setPhoneNumber(req.getPhoneNumber() != null ? req.getPhoneNumber().trim() : null);
        sim.setCarrierName(req.getCarrierName() != null ? req.getCarrierName().trim() : null);
        if (req.getImsi() != null) sim.setImsi(req.getImsi().trim());
        if (req.getImei() != null) sim.setImei(req.getImei().trim());
        if (req.getSlotIndex() != null) sim.setSlotIndex(req.getSlotIndex());
        return ResponseEntity.ok(simCardRepository.save(sim));
    }

    @Data
    public static class AssignSimRequest {
        private Long deviceId;
        private Integer slotIndex;
    }

    @Data
    public static class EditSimRequest {
        private String iccid;
        private String phoneNumber;
        private String carrierName;
        private String imsi;
        private String imei;
        private Integer slotIndex;
    }
}
