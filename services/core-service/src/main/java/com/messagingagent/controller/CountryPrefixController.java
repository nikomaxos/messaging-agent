package com.messagingagent.controller;

import com.messagingagent.model.Country;
import com.messagingagent.model.Network;
import com.messagingagent.repository.CountryRepository;
import com.messagingagent.repository.NetworkRepository;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping("/api/routing/prefixes")
@RequiredArgsConstructor
public class CountryPrefixController {

    private final CountryRepository countryRepository;
    private final NetworkRepository networkRepository;

    @GetMapping
    public List<Country> getAll() {
        return countryRepository.findAll();
    }

    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<?> bulkCreateOrUpdate(@RequestBody List<Country> payloadCountries) {
        int processedCount = 0;
        
        // Build a global set of all existing prefixes to protect manually moved prefixes
        List<Network> allNetworks = networkRepository.findAll();
        java.util.Set<String> globalPrefixes = new java.util.HashSet<>();
        for (Network net : allNetworks) {
            if (net.getPrefixes() != null) {
                globalPrefixes.addAll(net.getPrefixes());
            }
        }
        
        for (Country incCountry : payloadCountries) {
            // Find existing country by name or ISO
            Country existingCountry = countryRepository.findByName(incCountry.getName()).orElse(null);
            
            if (existingCountry == null) {
                // If country doesn't exist, simply save the new country and its networks
                if (incCountry.getNetworks() != null) {
                    for (Network n : incCountry.getNetworks()) {
                        n.setCountry(incCountry);
                        if (n.getPrefixes() != null) {
                            java.util.List<String> validPrefixes = new java.util.ArrayList<>();
                            for (String p : n.getPrefixes()) {
                                if (!globalPrefixes.contains(p)) {
                                    validPrefixes.add(p);
                                    globalPrefixes.add(p);
                                }
                            }
                            n.setPrefixes(validPrefixes);
                        }
                    }
                }
                countryRepository.save(incCountry);
                processedCount++;
            } else {
                // Country exists, we must merge MCCs and Networks
                if (incCountry.getMccs() != null) {
                    for (String mcc : incCountry.getMccs()) {
                        if (!existingCountry.getMccs().contains(mcc)) {
                            existingCountry.getMccs().add(mcc);
                        }
                    }
                }
                
                if (incCountry.getNetworks() != null) {
                    for (Network incNet : incCountry.getNetworks()) {
                        // Look for existing network by name in this country
                        Optional<Network> existingNetOpt = existingCountry.getNetworks().stream()
                            .filter(n -> n.getName().equalsIgnoreCase(incNet.getName()))
                            .findFirst();
                            
                        if (existingNetOpt.isPresent()) {
                            // Network exists, merge MNCs ONLY. Do NOT touch prefixes, notes, or operatingStatus.
                            Network existingNet = existingNetOpt.get();
                            if (incNet.getMncs() != null) {
                                for (String mnc : incNet.getMncs()) {
                                    if (!existingNet.getMncs().contains(mnc)) {
                                        existingNet.getMncs().add(mnc);
                                    }
                                }
                            }
                            // Merge Prefixes only if they don't exist ANYWHERE in the system
                            if (incNet.getPrefixes() != null) {
                                for (String p : incNet.getPrefixes()) {
                                    if (!globalPrefixes.contains(p)) {
                                        if (existingNet.getPrefixes() == null) {
                                            existingNet.setPrefixes(new java.util.ArrayList<>());
                                        }
                                        existingNet.getPrefixes().add(p);
                                        globalPrefixes.add(p);
                                    }
                                }
                            }
                        } else {
                            // New network
                            incNet.setCountry(existingCountry);
                            if (incNet.getPrefixes() != null) {
                                java.util.List<String> validPrefixes = new java.util.ArrayList<>();
                                for (String p : incNet.getPrefixes()) {
                                    if (!globalPrefixes.contains(p)) {
                                        validPrefixes.add(p);
                                        globalPrefixes.add(p);
                                    }
                                }
                                incNet.setPrefixes(validPrefixes);
                            }
                            existingCountry.getNetworks().add(incNet);
                        }
                    }
                }
                countryRepository.save(existingCountry);
                processedCount++;
            }
        }
        
        return ResponseEntity.ok(processedCount);
    }

    @PutMapping("/country/{id}")
    @Transactional
    public ResponseEntity<Country> updateCountry(@PathVariable Long id, @RequestBody Country details) {
        return countryRepository.findById(id).map(c -> {
            c.setMccs(details.getMccs());
            c.setNotes(details.getNotes());
            c.setQuietHoursStart(details.getQuietHoursStart());
            c.setQuietHoursEnd(details.getQuietHoursEnd());
            c.setHasDndList(details.isHasDndList());
            return ResponseEntity.ok(countryRepository.save(c));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/network/{id}")
    @Transactional
    public ResponseEntity<Network> updateNetwork(@PathVariable Long id, @RequestBody Network details) {
        return networkRepository.findById(id).map(n -> {
            n.setMncs(details.getMncs());
            n.setPrefixes(details.getPrefixes());
            n.setOperatingStatus(details.getOperatingStatus());
            n.setNotes(details.getNotes());
            return ResponseEntity.ok(networkRepository.save(n));
        }).orElse(ResponseEntity.notFound().build());
    }

    @Data
    public static class ResolveResponse {
        private boolean valid;
        private String countryCode;
        private String nationalNumber;
        private String region;
        private String carrier;
        private String numberType;
    }

    @GetMapping("/resolve")
    public ResponseEntity<ResolveResponse> resolvePrefix(@RequestParam("number") String number) {
        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        PhoneNumberToCarrierMapper carrierMapper = PhoneNumberToCarrierMapper.getInstance();
        ResolveResponse response = new ResolveResponse();
        
        try {
            // we assume the number starts with + for parsing, if not we add it
            if (!number.startsWith("+")) {
                number = "+" + number;
            }
            PhoneNumber parsed = phoneUtil.parse(number, "");
            response.setValid(phoneUtil.isValidNumber(parsed));
            response.setCountryCode(String.valueOf(parsed.getCountryCode()));
            response.setNationalNumber(String.valueOf(parsed.getNationalNumber()));
            response.setRegion(phoneUtil.getRegionCodeForNumber(parsed));
            response.setCarrier(carrierMapper.getNameForNumber(parsed, Locale.ENGLISH));
            response.setNumberType(phoneUtil.getNumberType(parsed).name());
            
            return ResponseEntity.ok(response);
        } catch (NumberParseException e) {
            response.setValid(false);
            return ResponseEntity.badRequest().body(response);
        }
    }
}
