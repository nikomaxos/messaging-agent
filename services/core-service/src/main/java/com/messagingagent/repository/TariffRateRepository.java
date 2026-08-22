package com.messagingagent.repository;

import com.messagingagent.model.TariffRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TariffRateRepository extends JpaRepository<TariffRate, Long> {
    List<TariffRate> findByTariffPlanId(Long planId);
}
