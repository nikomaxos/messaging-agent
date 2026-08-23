package com.messagingagent.service;

import com.messagingagent.dto.AccountDto;
import com.messagingagent.dto.UsernameDto;
import com.messagingagent.model.Account;
import com.messagingagent.model.Username;
import com.messagingagent.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    public List<AccountDto> getAllAccounts() {
        return accountRepository.findAll().stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Transactional
    public AccountDto createAccount(AccountDto dto) {
        Account account = new Account();
        updateEntityFromDto(account, dto);
        account = accountRepository.save(account);
        return mapToDto(account);
    }

    @Transactional
    public AccountDto updateAccount(Long id, AccountDto dto) {
        Account account = accountRepository.findById(id).orElseThrow(() -> new RuntimeException("Account not found"));
        updateEntityFromDto(account, dto);
        account = accountRepository.save(account);
        return mapToDto(account);
    }

    @Transactional
    public void deleteAccount(Long id) {
        accountRepository.deleteById(id);
    }

    private void updateEntityFromDto(Account account, AccountDto dto) {
        account.setName(dto.getName());
        if (dto.getType() != null) account.setType(dto.getType());
        account.setCompanyName(dto.getCompanyName());
        account.setVatNumber(dto.getVatNumber());
        account.setAddress(dto.getAddress());
        account.setEmail(dto.getEmail());
        account.setContactPerson(dto.getContactPerson());

        if (dto.getUsernames() != null) {
            if (account.getUsernames() == null) {
                account.setUsernames(new ArrayList<>());
            }
            
            // Map existing usernames by ID
            java.util.Map<Long, Username> existingUsernames = account.getUsernames().stream()
                .filter(u -> u.getId() != null)
                .collect(Collectors.toMap(Username::getId, u -> u));
                
            account.getUsernames().clear();
            
            for (UsernameDto uDto : dto.getUsernames()) {
                Username u;
                if (uDto.getId() != null && existingUsernames.containsKey(uDto.getId())) {
                    u = existingUsernames.get(uDto.getId());
                } else {
                    u = new Username();
                    u.setAccount(account);
                }
                
                u.setUsername(uDto.getUsername());
                u.setWhitelistedIps(uDto.getWhitelistedIps());
                u.setEnforceIpWhitelist(uDto.isEnforceIpWhitelist());
                u.setSmppEnabled(uDto.isSmppEnabled());
                u.setApiEnabled(uDto.isApiEnabled());
                u.setWebEnabled(uDto.isWebEnabled());
                u.setBanned(uDto.isBanned());
                
                account.getUsernames().add(u);
            }
        }
    }

    private AccountDto mapToDto(Account account) {
        AccountDto dto = new AccountDto();
        dto.setId(account.getId());
        dto.setName(account.getName());
        dto.setType(account.getType());
        dto.setCompanyName(account.getCompanyName());
        dto.setVatNumber(account.getVatNumber());
        dto.setAddress(account.getAddress());
        dto.setEmail(account.getEmail());
        dto.setContactPerson(account.getContactPerson());
        
        if (account.getUsernames() != null) {
            dto.setUsernames(account.getUsernames().stream().map(u -> {
                UsernameDto uDto = new UsernameDto();
                uDto.setId(u.getId());
                uDto.setUsername(u.getUsername());
                uDto.setAccountId(account.getId());
                uDto.setWhitelistedIps(u.getWhitelistedIps());
                uDto.setEnforceIpWhitelist(u.isEnforceIpWhitelist());
                uDto.setSmppEnabled(u.isSmppEnabled());
                uDto.setApiEnabled(u.isApiEnabled());
                uDto.setWebEnabled(u.isWebEnabled());
                uDto.setBanned(u.isBanned());
                uDto.setCreatedAt(u.getCreatedAt());
                uDto.setUpdatedAt(u.getUpdatedAt());
                return uDto;
            }).collect(Collectors.toList()));
        } else {
            dto.setUsernames(new ArrayList<>());
        }
        
        dto.setCreatedAt(account.getCreatedAt());
        dto.setUpdatedAt(account.getUpdatedAt());
        return dto;
    }
}
