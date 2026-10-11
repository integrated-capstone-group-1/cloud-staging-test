package com.capstone.accounts.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.exception.ResourceNotFoundException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AccountHoldService {

    private final AccountRepository accountRepository;
    private final AccountHoldRepository accountHoldRepository;
    private final BalanceCacheService balanceCacheService;

    @Autowired
    public AccountHoldService(
            @Qualifier("accountRepository") AccountRepository accountRepository,
            AccountHoldRepository accountHoldRepository,
            BalanceCacheService balanceCacheService) {
        this.accountRepository = accountRepository;
        this.accountHoldRepository = accountHoldRepository;
        this.balanceCacheService = balanceCacheService;
    }

    @Transactional
    public HoldResponse createHold(String accountId, CreateHoldRequest request) {
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Hold amount must be greater than zero");
        }
        if (request.currency() == null || request.currency().isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }

        AccountMaster account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found"));

        if (!"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
            throw new IllegalStateException("Account " + accountId + " is not ACTIVE");
        }

        if (!account.getCurrencyCode().trim().equalsIgnoreCase(request.currency().trim())) {
            throw new IllegalArgumentException("Account " + accountId + " currency mismatch");
        }

        BigDecimal availableBalance = account.getAvailableBalance();
        if (availableBalance.compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException("Insufficient funds on account " + accountId);
        }

        BigDecimal currentHeld = account.getHeldAmount() != null ? account.getHeldAmount() : BigDecimal.ZERO;
        account.setHeldAmount(currentHeld.add(request.amount()));
        account.setUpdatedAt(LocalDateTime.now());
        account.setUpdatedBy("HOLD_ENGINE");
        accountRepository.save(account);

        String holdId = UUID.randomUUID().toString();
        int hours = (request.expiresInHours() != null && request.expiresInHours() > 0) ? request.expiresInHours() : 24;
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(hours);

        AccountHold hold = AccountHold.builder()
                .holdId(holdId)
                .accountId(accountId)
                .amount(request.amount())
                .currencyCode(account.getCurrencyCode().trim())
                .holdType(request.holdType() != null && !request.holdType().isBlank() ? request.holdType().toUpperCase() : "RISK")
                .status("ACTIVE")
                .reason(request.reason())
                .expiresAt(expiresAt)
                .createdAt(LocalDateTime.now())
                .createdBy("HOLD_ENGINE")
                .build();

        accountHoldRepository.save(hold);
        balanceCacheService.put(accountId, account.getBalanceAmount());
        return new HoldResponse(hold.getHoldId(), hold.getStatus(), hold.getAmount());
    }

    @Transactional
    public ReleaseHoldResponse releaseHold(String accountId, String holdId) {
        AccountMaster account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found"));

        AccountHold hold = accountHoldRepository.findByIdForUpdate(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold " + holdId + " not found"));

        if (!accountId.equals(hold.getAccountId())) {
            throw new IllegalArgumentException("Hold " + holdId + " does not belong to account " + accountId);
        }

        if (!"ACTIVE".equalsIgnoreCase(hold.getStatus())) {
            throw new IllegalStateException("Hold " + holdId + " is not ACTIVE");
        }

        BigDecimal currentHeld = account.getHeldAmount() != null ? account.getHeldAmount() : BigDecimal.ZERO;
        BigDecimal newHeld = currentHeld.subtract(hold.getAmount());
        if (newHeld.compareTo(BigDecimal.ZERO) < 0) {
            newHeld = BigDecimal.ZERO;
        }
        account.setHeldAmount(newHeld);
        account.setUpdatedAt(LocalDateTime.now());
        account.setUpdatedBy("HOLD_ENGINE");
        accountRepository.save(account);

        hold.setStatus("RELEASED");
        hold.setUpdatedAt(LocalDateTime.now());
        hold.setUpdatedBy("HOLD_ENGINE");
        accountHoldRepository.save(hold);

        balanceCacheService.put(accountId, account.getBalanceAmount());
        return new ReleaseHoldResponse(hold.getHoldId(), "RELEASED");
    }

    @Transactional(readOnly = true)
    public List<AccountHold> getActiveHoldsForAccount(String accountId) {
        return accountHoldRepository.findByAccountIdAndStatus(accountId, "ACTIVE");
    }

    @Transactional(readOnly = true)
    public AccountHold getHold(String holdId) {
        return accountHoldRepository.findById(holdId)
                .orElseThrow(() -> new ResourceNotFoundException("Hold " + holdId + " not found"));
    }
}
