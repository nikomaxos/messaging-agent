package com.messagingagent.repository;

import com.messagingagent.model.RoutingRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RoutingRateLimitRepository extends JpaRepository<RoutingRateLimit, Long> {
    Optional<RoutingRateLimit> findByCustomerProfileIdAndCountryCodeAndNetworkIdAndSupplierId(
            String customerProfileId, String countryCode, String networkId, String supplierId);
}
