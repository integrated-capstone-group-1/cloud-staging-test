package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryJob {

    private final AccountHoldRepository accountHoldRepository;
    private final AccountRepository accountRepository;

    @Scheduled(fixedDelayString = "${accounts.hold-expiry-poll-ms:5000}")
    @Transactional
    public void expireHolds() {
        LocalDateTime now = LocalDateTime.now();
        List<AccountHold> expiredHolds = accountHoldRepository.findByStatusAndExpiresAtBefore("ACTIVE", now);

        if (expiredHolds.isEmpty()) {
            return;
        }

        log.info("Found {} expired holds to process", expiredHolds.size());

        for (AccountHold hold : expiredHolds) {
            try {
                processExpiry(hold);
            } catch (Exception e) {
                log.error("Failed to process expiry for hold: {}", hold.getHoldId(), e);
            }
        }
    }

    private void processExpiry(AccountHold hold) {
        AccountMaster account = accountRepository.findByIdForUpdate(hold.getAccountId())
                .orElse(null);

        if (account == null) {
            log.warn("Account {} not found for hold {}, marking hold as EXPIRED", hold.getAccountId(), hold.getHoldId());
            hold.setStatus("EXPIRED");
            accountHoldRepository.save(hold);
            return;
        }

        BigDecimal currentHeld = account.getHeldAmount() != null ? account.getHeldAmount() : BigDecimal.ZERO;
        BigDecimal newHeld = currentHeld.subtract(hold.getAmount());

        if (newHeld.compareTo(BigDecimal.ZERO) < 0) {
            newHeld = BigDecimal.ZERO;
        }

        account.setHeldAmount(newHeld);
        account.setUpdatedAt(LocalDateTime.now());
        account.setUpdatedBy("HOLD_EXPIRY_JOB");
        accountRepository.save(account);

        hold.setStatus("EXPIRED");
        hold.setUpdatedAt(LocalDateTime.now());
        hold.setUpdatedBy("HOLD_EXPIRY_JOB");
        accountHoldRepository.save(hold);

        log.info("Hold {} for account {} expired and released", hold.getHoldId(), hold.getAccountId());
    }
}
