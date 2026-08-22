package com.messagingagent.service;

import com.messagingagent.dto.ClientBillingDto;
import com.messagingagent.dto.TariffPlanDto;
import com.messagingagent.dto.TariffRateDto;
import com.messagingagent.dto.TopUpRequestDto;
import com.messagingagent.model.*;
import com.messagingagent.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    private final TariffPlanRepository tariffPlanRepository;
    private final TariffRateRepository tariffRateRepository;
    private final ClientBillingRepository clientBillingRepository;
    private final BillingTransactionRepository billingTransactionRepository;
    private final SmppClientRepository smppClientRepository;
    private final StringRedisTemplate redisTemplate;

    public List<TariffPlanDto> getTariffPlans() {
        return tariffPlanRepository.findAll().stream().map(p -> {
            TariffPlanDto dto = new TariffPlanDto();
            dto.setId(p.getId());
            dto.setName(p.getName());
            dto.setCurrency(p.getCurrency());
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public TariffPlanDto createTariffPlan(TariffPlanDto dto) {
        TariffPlan plan = new TariffPlan();
        plan.setName(dto.getName());
        plan.setCurrency(dto.getCurrency() != null ? dto.getCurrency() : "EUR");
        plan = tariffPlanRepository.save(plan);
        dto.setId(plan.getId());
        return dto;
    }

    public List<TariffRateDto> getTariffRates(Long planId) {
        return tariffRateRepository.findByTariffPlanId(planId).stream().map(r -> {
            TariffRateDto dto = new TariffRateDto();
            dto.setId(r.getId());
            dto.setPlanId(planId);
            dto.setPrefix(r.getPrefix());
            dto.setRate(r.getRate());
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public TariffRateDto addTariffRate(Long planId, TariffRateDto dto) {
        TariffPlan plan = tariffPlanRepository.findById(planId)
                .orElseThrow(() -> new RuntimeException("Tariff plan not found"));

        TariffRate rate = new TariffRate();
        rate.setTariffPlan(plan);
        rate.setPrefix(dto.getPrefix());
        rate.setRate(dto.getRate());
        rate = tariffRateRepository.save(rate);
        
        // Sync to Redis immediately
        redisTemplate.opsForValue().set("tariff:" + planId + ":" + dto.getPrefix(), dto.getRate().toString());

        dto.setId(rate.getId());
        dto.setPlanId(planId);
        return dto;
    }

    @Transactional
    public void deleteTariffRate(Long planId, Long rateId) {
        TariffRate rate = tariffRateRepository.findById(rateId).orElse(null);
        if (rate != null) {
            tariffRateRepository.delete(rate);
            redisTemplate.delete("tariff:" + planId + ":" + rate.getPrefix());
        }
    }

    public List<ClientBillingDto> getAllClientBilling() {
        return clientBillingRepository.findAll().stream().map(b -> {
            ClientBillingDto dto = new ClientBillingDto();
            dto.setClientId(b.getClientId());
            if (b.getClient() != null) {
                dto.setClientName(b.getClient().getName());
            }
            dto.setBillingType(b.getBillingType());
            
            // Get live balance from Redis, fallback to DB
            String systemId = b.getClient().getSystemId();
            String liveBalanceStr = redisTemplate.opsForValue().get("balance:" + systemId);
            if (liveBalanceStr != null) {
                dto.setBalance(new BigDecimal(liveBalanceStr));
            } else {
                dto.setBalance(b.getBalance());
                redisTemplate.opsForValue().set("balance:" + systemId, b.getBalance().toString());
            }
            
            dto.setCreditLimit(b.getCreditLimit());
            if (b.getTariffPlan() != null) {
                dto.setTariffPlanId(b.getTariffPlan().getId());
                dto.setTariffPlanName(b.getTariffPlan().getName());
            }
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public ClientBillingDto updateClientBilling(Long clientId, ClientBillingDto dto) {
        ClientBilling billing = clientBillingRepository.findById(clientId).orElse(null);
        if (billing == null) {
            SmppClient client = smppClientRepository.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client not found"));
            billing = new ClientBilling();
            billing.setClient(client);
            billing.setClientId(clientId);
            billing.setBalance(BigDecimal.ZERO);
            redisTemplate.opsForValue().set("balance:" + client.getSystemId(), "0");
        }

        billing.setBillingType(dto.getBillingType());
        billing.setCreditLimit(dto.getCreditLimit() != null ? dto.getCreditLimit() : BigDecimal.ZERO);
        
        if (dto.getTariffPlanId() != null) {
            TariffPlan plan = tariffPlanRepository.findById(dto.getTariffPlanId())
                    .orElseThrow(() -> new RuntimeException("Tariff plan not found"));
            billing.setTariffPlan(plan);
            redisTemplate.opsForValue().set("client_plan:" + billing.getClient().getSystemId(), plan.getId().toString());
            redisTemplate.opsForValue().set("client_type:" + billing.getClient().getSystemId(), dto.getBillingType());
        }

        billing = clientBillingRepository.save(billing);
        
        dto.setClientId(clientId);
        return dto;
    }

    @Transactional
    public void topUp(Long clientId, TopUpRequestDto request) {
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Top up amount must be positive");
        }

        ClientBilling billing = clientBillingRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Billing profile not found"));

        // Increase in DB
        billing.setBalance(billing.getBalance().add(request.getAmount()));
        clientBillingRepository.save(billing);

        // Increase in Redis atomically
        redisTemplate.opsForValue().increment("balance:" + billing.getClient().getSystemId(), request.getAmount().doubleValue());

        // Create Transaction Record
        BillingTransaction tx = new BillingTransaction();
        tx.setClient(billing.getClient());
        tx.setAmount(request.getAmount());
        tx.setType("TOPUP");
        tx.setDescription(request.getDescription());
        billingTransactionRepository.save(tx);
        
        log.info("TopUp successful: Client {} added {}", clientId, request.getAmount());
    }
}
