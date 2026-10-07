package com.capstone.accounts.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.OutboxMaster;
import com.capstone.accounts.entity.PostingReference;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.LedgerEntryRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.accounts.repository.PostingReferenceRepository;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.InsufficientBalanceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoreSettlementService {

    private final AccountRepository accountRepository;
    private final AccountHoldRepository accountHoldRepository;
    private final PostingReferenceRepository postingReferenceRepository;
    private final OutboxMasterRepository outboxMasterRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final BalanceCacheService balanceCacheService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CoreSettlementResponse settle(CoreSettlementRequest request) {
        // 1. Idempotency check via SHA-256 payload hash
        String requestHash = computeSha256Hash(request);
        Optional<PostingReference> existing = postingReferenceRepository.findByPostingReferenceId(request.postingReferenceId());

        if (existing.isPresent()) {
            PostingReference ref = existing.get();
            if (ref.getRequestHash().equals(requestHash)) {
                log.info("Duplicate settlement request detected for postingReferenceId: {}. Returning existing record.", request.postingReferenceId());
                return new CoreSettlementResponse(
                    ref.getPostingReferenceId(),
                    ref.getCoreReferenceId(),
                    ref.getStatus(),
                    ref.getSettledAt()
                );
            }
            throw new IllegalArgumentException("Posting reference ID " + request.postingReferenceId() + " conflict with different payload hash");
        }

        // 2. Deterministic ascending row locking to prevent deadlocks (ORDER BY account_id ASC)
        List<String> accountIds = request.legs().stream()
                .map(SettlementLegDto::accountId)
                .distinct()
                .sorted()
                .toList();

        List<AccountMaster> lockedAccounts = accountRepository.findAllByIdsForUpdate(accountIds);
        Map<String, AccountMaster> accountMap = lockedAccounts.stream()
                .collect(Collectors.toMap(AccountMaster::getAccountId, Function.identity()));

        if (accountMap.size() != accountIds.size()) {
            throw new IllegalArgumentException("One or more accounts in the settlement request were not found");
        }

        // 3. Optional hold capture handling
        if (request.captureHoldId() != null && !request.captureHoldId().isBlank()) {
            AccountHold hold = accountHoldRepository.findByIdForUpdate(request.captureHoldId())
                    .orElseThrow(() -> new IllegalArgumentException("Hold ID " + request.captureHoldId() + " not found"));

            if (!"ACTIVE".equalsIgnoreCase(hold.getStatus())) {
                throw new IllegalStateException("Hold " + request.captureHoldId() + " is not ACTIVE");
            }

            AccountMaster holdAccount = accountMap.get(hold.getAccountId());
            if (holdAccount == null) {
                throw new IllegalArgumentException("Account associated with hold " + request.captureHoldId() + " not in settlement payload");
            }

            BigDecimal currentHeld = holdAccount.getHeldAmount() != null ? holdAccount.getHeldAmount() : BigDecimal.ZERO;
            BigDecimal newHeld = currentHeld.subtract(hold.getAmount());
            holdAccount.setHeldAmount(newHeld.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : newHeld);

            hold.setStatus("CAPTURED");
            hold.setUpdatedAt(LocalDateTime.now());
            hold.setUpdatedBy("SETTLEMENT_ENGINE");
            accountHoldRepository.save(hold);
        }

        // 4. Validate available balances and mutate balances across legs
        for (SettlementLegDto leg : request.legs()) {
            AccountMaster account = accountMap.get(leg.accountId());

            if (!"ACTIVE".equalsIgnoreCase(account.getAccountStatus())) {
                throw new IllegalStateException("Account " + account.getAccountId() + " is not ACTIVE");
            }

            if ("DEBIT".equalsIgnoreCase(leg.direction())) {
                BigDecimal available = account.getAvailableBalance();
                if (available.compareTo(leg.amount()) < 0) {
                    throw new InsufficientBalanceException("Insufficient available balance on account " + account.getAccountId());
                }
                account.setBalanceAmount(account.getBalanceAmount().subtract(leg.amount()));
            } else if ("CREDIT".equalsIgnoreCase(leg.direction())) {
                account.setBalanceAmount(account.getBalanceAmount().add(leg.amount()));
            } else {
                throw new IllegalArgumentException("Invalid settlement leg direction: " + leg.direction());
            }

            account.setUpdatedAt(LocalDateTime.now());
            account.setUpdatedBy("SETTLEMENT_ENGINE");
        }

        accountRepository.saveAll(accountMap.values());

        // 5. Store Posting Reference record
        String coreReferenceId = UUID.randomUUID().toString();
        PostingReference postingReference = new PostingReference();
        postingReference.setPostingReferenceId(request.postingReferenceId());
        postingReference.setCoreReferenceId(coreReferenceId);
        postingReference.setRequestHash(requestHash);
        postingReference.setStatus("SETTLED");
        postingReference.setSettledAt(LocalDateTime.now());
        postingReferenceRepository.save(postingReference);

        // 6. Transactional Outbox insertion in same local transaction
        OutboxMaster outboxEvent = OutboxMaster.builder()
                .outboxId(UUID.randomUUID().toString())
                .sourceService("accounts-service")
                .aggregateType("SETTLEMENT")
                .aggregateId(request.postingReferenceId())
                .eventType("SETTLEMENT_COMPLETED")
                .payload(serializePayload(request))
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();
        outboxMasterRepository.save(outboxEvent);

        // 7. Invalidate Redis balance cache for all updated accounts
        for (String accountId : accountIds) {
            AccountMaster acc = accountMap.get(accountId);
            balanceCacheService.put(accountId, acc.getBalanceAmount());
        }

        return new CoreSettlementResponse(
            postingReference.getPostingReferenceId(),
            postingReference.getCoreReferenceId(),
            postingReference.getStatus(),
            postingReference.getSettledAt()
        );
    }

    @Transactional(readOnly = true)
    public CoreSettlementResponse getSettlementStatus(String postingReferenceId) {
        PostingReference ref = postingReferenceRepository.findByPostingReferenceId(postingReferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement record " + postingReferenceId + " not found"));

        return new CoreSettlementResponse(
            ref.getPostingReferenceId(),
            ref.getCoreReferenceId(),
            ref.getStatus(),
            ref.getSettledAt()
        );
    }

    private String computeSha256Hash(CoreSettlementRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to calculate SHA-256 request hash", e);
        }
    }

    private String serializePayload(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }
    }
}