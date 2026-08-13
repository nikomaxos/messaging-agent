package com.messagingagent.repository;

import com.messagingagent.model.CountryPrefix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CountryPrefixRepository extends JpaRepository<CountryPrefix, Long> {
    List<CountryPrefix> findByActiveTrue();
    List<CountryPrefix> findByCountryNameIgnoreCase(String countryName);
}
