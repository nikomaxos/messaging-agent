package com.messagingagent.controller;

import com.messagingagent.dto.ClientBillingDto;
import com.messagingagent.dto.TariffPlanDto;
import com.messagingagent.dto.TariffRateDto;
import com.messagingagent.dto.TopUpRequestDto;
import com.messagingagent.service.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @GetMapping("/tariffs")
    public ResponseEntity<List<TariffPlanDto>> getTariffPlans() {
        return ResponseEntity.ok(billingService.getTariffPlans());
    }

    @PostMapping("/tariffs")
    public ResponseEntity<TariffPlanDto> createTariffPlan(@RequestBody TariffPlanDto dto) {
        return ResponseEntity.ok(billingService.createTariffPlan(dto));
    }

    @GetMapping("/tariffs/{planId}/rates")
    public ResponseEntity<List<TariffRateDto>> getTariffRates(@PathVariable Long planId) {
        return ResponseEntity.ok(billingService.getTariffRates(planId));
    }

    @PostMapping("/tariffs/{planId}/rates")
    public ResponseEntity<TariffRateDto> addTariffRate(@PathVariable Long planId, @RequestBody TariffRateDto dto) {
        return ResponseEntity.ok(billingService.addTariffRate(planId, dto));
    }

    @DeleteMapping("/tariffs/{planId}/rates/{rateId}")
    public ResponseEntity<Void> deleteTariffRate(@PathVariable Long planId, @PathVariable Long rateId) {
        billingService.deleteTariffRate(planId, rateId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/clients")
    public ResponseEntity<List<ClientBillingDto>> getAllClientBilling() {
        return ResponseEntity.ok(billingService.getAllClientBilling());
    }

    @PutMapping("/clients/{clientId}")
    public ResponseEntity<ClientBillingDto> updateClientBilling(@PathVariable Long clientId, @RequestBody ClientBillingDto dto) {
        return ResponseEntity.ok(billingService.updateClientBilling(clientId, dto));
    }

    @PostMapping("/clients/{clientId}/topup")
    public ResponseEntity<Void> topUp(@PathVariable Long clientId, @RequestBody TopUpRequestDto dto) {
        billingService.topUp(clientId, dto);
        return ResponseEntity.ok().build();
    }
}
