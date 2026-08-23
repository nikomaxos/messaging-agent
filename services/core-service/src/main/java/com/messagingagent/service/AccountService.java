package com.messagingagent.service;

import com.messagingagent.dto.AccountDto;
import com.messagingagent.model.Account;
import com.messagingagent.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
        account.setWhitelistedIps(dto.getWhitelistedIps());
        account.setEnforceIpWhitelist(dto.isEnforceIpWhitelist());
        account.setSmppEnabled(dto.isSmppEnabled());
        account.setApiEnabled(dto.isApiEnabled());
        account.setWebEnabled(dto.isWebEnabled());
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
        dto.setWhitelistedIps(account.getWhitelistedIps());
        dto.setEnforceIpWhitelist(account.isEnforceIpWhitelist());
        dto.setSmppEnabled(account.isSmppEnabled());
        dto.setApiEnabled(account.isApiEnabled());
        dto.setWebEnabled(account.isWebEnabled());
        dto.setCreatedAt(account.getCreatedAt());
        dto.setUpdatedAt(account.getUpdatedAt());
        return dto;
    }
}
