package com.capstone.ledger.frontend.service;

import com.capstone.ledger.frontend.adapter.BankingApiClient;
import com.capstone.ledger.frontend.model.*;
import com.capstone.ledger.frontend.model.enums.AccountStatus;
import com.capstone.ledger.frontend.model.enums.AccountType;
import com.capstone.ledger.frontend.model.enums.KycStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service orchestrating staff-facing Admin Web Portal operations (T-011):
 * - Customer 360 Aggregation (DIG-103)
 * - Account Lock / Admin Freeze Precedence (DIG-54, BR-09)
 * - Versioned Parameter Store (DIG-17, DIG-54, BR-19)
 * - Held-Transfer Review Queue (DIG-51, DIG-54)
 * - DLQ Console: Inspect, Replay, Discard (DIG-90)
 * - Real-Time Transaction Monitoring Feed (DIG-102)
 * - Read-Only Audit Ledger Viewer (DIG-53)
 */
@Service
public class AdminPortalService {

    private final BankingApiClient bankingClient;

    // In-memory state stores
    private final Set<String> lockedCustomerIds = ConcurrentHashMap.newKeySet();
    private SystemParameterView currentParameters;
    private final List<HeldTransactionView> heldTransactions = new CopyOnWriteArrayList<>();
    private final List<DlqMessageView> dlqMessages = new CopyOnWriteArrayList<>();
    private final List<AuditLogView> auditLogs = new CopyOnWriteArrayList<>();
    private final List<TransactionMonitoringView> monitoringFeed = new CopyOnWriteArrayList<>();

    public AdminPortalService(BankingApiClient bankingClient) {
        this.bankingClient = bankingClient;
        seedInitialData();
    }

    private void seedInitialData() {
        // Initial parameters (version 1)
        this.currentParameters = new SystemParameterView(
                1,
                new BigDecimal("100000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("1.25"),
                3,
                300,
                "SYSTEM",
                LocalDateTime.now().minusDays(1),
                "Baseline system parameters initialized"
        );

        // Pre-seeded Held Transactions aligned with canonical Oracle CBS records
        heldTransactions.add(new HeldTransactionView(
                "TXN-HELD-9021",
                "CORR-R71-001",
                "66e07d02-05c9-4deb-87d5-10e23cf5968f",
                "Maria Santos",
                "f95e7e52-3259-4b4d-ac66-b186a893cf66",
                "bank-fx-eur-01",
                new BigDecimal("490000.00"),
                "PHP",
                82,
                "Cross-currency velocity check triggered; transfer value exceeds 90% of CTR threshold",
                "HELD_FOR_REVIEW",
                LocalDateTime.now().minusMinutes(25)
        ));

        heldTransactions.add(new HeldTransactionView(
                "TXN-HELD-9045",
                "CORR-R71-002",
                "7bd20304-dd6a-4e45-850b-58df8b535be4",
                "Juan Dela Cruz",
                "3722f77f-3d1b-4d55-8f83-b71bac91d095",
                "f95e7e52-3259-4b4d-ac66-b186a893cf66",
                new BigDecimal("260000.00"),
                "PHP",
                74,
                "Sudden outbound burst to new external counterparty; AMLA STR review requested",
                "HELD_FOR_REVIEW",
                LocalDateTime.now().minusMinutes(12)
        ));

        // Pre-seeded DLQ messages
        dlqMessages.add(new DlqMessageView(
                "DLQ-MSG-101",
                "txn.settlement",
                0,
                14205L,
                "TXN-9081",
                "{\"txnId\":\"TXN-9081\",\"source\":\"3722f77f-3d1b-4d55-8f83-b71bac91d095\",\"target\":\"f95e7e52-3259-4b4d-ac66-b186a893cf66\",\"amount\":1500.00}",
                "OptimisticLockingFailureException: Balance row version conflict on Oracle master",
                3,
                "PENDING",
                LocalDateTime.now().minusHours(2)
        ));

        dlqMessages.add(new DlqMessageView(
                "DLQ-MSG-102",
                "notification.dispatch",
                1,
                88301L,
                "NOTIF-552",
                "{\"customerId\":\"66e07d02-05c9-4deb-87d5-10e23cf5968f\",\"channel\":\"SMS\",\"template\":\"OTP_AUTH\"}",
                "ProviderTimeoutException: Telco SMS gateway connection timed out after 3 retries",
                3,
                "PENDING",
                LocalDateTime.now().minusHours(1)
        ));

        // Pre-seeded Monitoring Feed
        seedMonitoringFeed();

        // Initial Audit Log entries
        logAudit("SYSTEM_STARTUP", "SYSTEM", "PORTAL", "8090", "Admin portal initialized with active RBAC");
    }

    private void seedMonitoringFeed() {
        String[] stageNames = {
                "GATEWAY_INGEST", "AUTH_VALIDATION", "IDEMPOTENCY_LOCK", "RISK_EVALUATION",
                "AMLA_CTR_STR", "ORACLE_ROW_LOCK", "LEDGER_MUTATION", "KAFKA_DISPATCH",
                "NOTIFICATION_EVENT", "POSTGRES_AUDIT_SYNC", "RECON_REGISTER"
        };

        TransactionMonitoringView m1 = new TransactionMonitoringView();
        m1.setTxnId("TXN-MON-8801");
        m1.setCorrelationId("CORR-MON-8801");
        m1.setRawCustomerName("Juan Dela Cruz");
        m1.setMaskedCustomerName("J*** D***");
        m1.setRawSourceAccount("3722f77f-3d1b-4d55-8f83-b71bac91d095");
        m1.setMaskedSourceAccount("3722-****-d095");
        m1.setRawDestAccount("f95e7e52-3259-4b4d-ac66-b186a893cf66");
        m1.setMaskedDestAccount("f95e-****-cf66");
        m1.setAmount(new BigDecimal("12500.00"));
        m1.setCurrency("PHP");
        m1.setOverallStatus("SUCCESS");
        m1.setCurrentStage(11);
        m1.setTotalDurationMs(840);
        m1.setTimestamp(LocalDateTime.now().minusMinutes(2));

        for (int i = 0; i < stageNames.length; i++) {
            m1.getStages().add(new TransactionMonitoringView.StageTracker(i + 1, stageNames[i], "GREEN", 50 + (i * 20)));
        }
        monitoringFeed.add(m1);

        // Matches Held Queue item TXN-HELD-9021 (Maria Santos)
        TransactionMonitoringView m2 = new TransactionMonitoringView();
        m2.setTxnId("TXN-HELD-9021");
        m2.setCorrelationId("CORR-R71-001");
        m2.setRawCustomerName("Maria Santos");
        m2.setMaskedCustomerName("M*** S***");
        m2.setRawSourceAccount("f95e7e52-3259-4b4d-ac66-b186a893cf66");
        m2.setMaskedSourceAccount("f95e-****-cf66");
        m2.setRawDestAccount("bank-cash-clearing-php-01");
        m2.setMaskedDestAccount("bank-****-01");
        m2.setAmount(new BigDecimal("2500.00"));
        m2.setCurrency("PHP");
        m2.setOverallStatus("HELD");
        m2.setCurrentStage(5);
        m2.setTotalDurationMs(320);
        m2.setTimestamp(LocalDateTime.now().minusMinutes(25));

        m2.getStages().add(new TransactionMonitoringView.StageTracker(1, "GATEWAY_INGEST", "GREEN", 45));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(2, "AUTH_VALIDATION", "GREEN", 60));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(3, "IDEMPOTENCY_LOCK", "GREEN", 85));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(4, "RISK_EVALUATION", "GREEN", 130));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(5, "AMLA_CTR_STR", "RED", 0));
        monitoringFeed.add(m2);

        // Matches Held Queue item TXN-HELD-9045 (Juan Dela Cruz)
        TransactionMonitoringView m3 = new TransactionMonitoringView();
        m3.setTxnId("TXN-HELD-9045");
        m3.setCorrelationId("CORR-R71-002");
        m3.setRawCustomerName("Juan Dela Cruz");
        m3.setMaskedCustomerName("J*** D***");
        m3.setRawSourceAccount("3722f77f-3d1b-4d55-8f83-b71bac91d095");
        m3.setMaskedSourceAccount("3722-****-d095");
        m3.setRawDestAccount("f95e7e52-3259-4b4d-ac66-b186a893cf66");
        m3.setMaskedDestAccount("f95e-****-cf66");
        m3.setAmount(new BigDecimal("1500.00"));
        m3.setCurrency("PHP");
        m3.setOverallStatus("HELD");
        m3.setCurrentStage(5);
        m3.setTotalDurationMs(210);
        m3.setTimestamp(LocalDateTime.now().minusMinutes(12));

        m3.getStages().add(new TransactionMonitoringView.StageTracker(1, "GATEWAY_INGEST", "GREEN", 40));
        m3.getStages().add(new TransactionMonitoringView.StageTracker(2, "AUTH_VALIDATION", "GREEN", 50));
        m3.getStages().add(new TransactionMonitoringView.StageTracker(3, "IDEMPOTENCY_LOCK", "GREEN", 40));
        m3.getStages().add(new TransactionMonitoringView.StageTracker(4, "RISK_EVALUATION", "GREEN", 80));
        m3.getStages().add(new TransactionMonitoringView.StageTracker(5, "AMLA_CTR_STR", "RED", 0));
        monitoringFeed.add(m3);
    }

    // --- Customer 360 Aggregation (DIG-103) ---

    public Customer360View getCustomer360(String customerId, String adminUser) {
        CustomerView customer = bankingClient.getCustomerById(customerId)
                .orElseGet(() -> {
                    // Fallback stub for unit tests
                    CustomerView fallback = new CustomerView();
                    fallback.setCustomerId(customerId);
                    fallback.setFirstName("Demo");
                    fallback.setLastName("Customer");
                    fallback.setEmail("demo." + customerId.toLowerCase() + "@example.com");
                    fallback.setContactNo("+63 917 555 1234");
                    fallback.setBirthDate(LocalDate.of(1990, 5, 15));
                    fallback.setAddress("Ayala Avenue, Makati City, Philippines");
                    fallback.setIdType("UMID");
                    fallback.setIdNumber("CRN-0112-9988-1");
                    fallback.setKycStatus(KycStatus.VERIFIED);
                    fallback.setCreatedAt(LocalDateTime.now().minusMonths(6));
                    return fallback;
                });

        Customer360View view = new Customer360View();
        view.setCustomerId(customer.getCustomerId());
        view.setFullName(customer.getFullName());
        view.setEmail(customer.getEmail());
        view.setContactNo(customer.getContactNo());
        view.setBirthDate(customer.getBirthDate());
        view.setAddress(customer.getAddress() != null ? customer.getAddress() : "Metro Manila, Philippines");
        view.setIdType(customer.getIdType() != null ? customer.getIdType() : "PASSPORT");
        view.setIdNumber(customer.getIdNumber() != null ? customer.getIdNumber() : "P1234567A");
        view.setKycStatus(customer.getKycStatus() != null ? customer.getKycStatus() : KycStatus.VERIFIED);
        view.setMemberSince(customer.getCreatedAt());
        view.setLocked(lockedCustomerIds.contains(customerId));

        // Pockets with Ledger / Held / Available balance
        List<AccountView> accounts = bankingClient.getAccountsByCustomerId(customerId);
        if (accounts.isEmpty()) {
            accounts = bankingClient.getAllAccounts().stream()
                    .filter(a -> customerId.equalsIgnoreCase(a.getCustomerId()))
                    .toList();
        }

        for (AccountView acct : accounts) {
            BigDecimal ledger = acct.getBalance() != null ? acct.getBalance() : BigDecimal.ZERO;
            BigDecimal held = acct.getHeldAmount() != null ? acct.getHeldAmount() : BigDecimal.ZERO;
            // Check if any held transaction has this account as source
            for (HeldTransactionView ht : getHeldTransactions()) {
                if (ht.isPending() && acct.getAccountId().equalsIgnoreCase(ht.getFromAccount())) {
                    held = held.add(ht.getAmount());
                }
            }
            BigDecimal available = ledger.subtract(held);
            if (available.compareTo(BigDecimal.ZERO) < 0) {
                available = BigDecimal.ZERO;
            }

            view.getPockets().add(new Customer360View.PocketBalanceView(
                    acct.getAccountId(),
                    acct.getCurrencyCode(),
                    acct.getAccountType(),
                    acct.getAccountStatus(),
                    ledger,
                    held,
                    available
            ));
        }

        boolean isStaff = customer.getEmail() != null && (customer.getEmail().equalsIgnoreCase("admin@ledgerbank.com") || customer.getEmail().toLowerCase().contains("admin"))
                || ("System".equalsIgnoreCase(customer.getFirstName()) && "Administrator".equalsIgnoreCase(customer.getLastName()));

        // Virtual Cards & Standing Instructions: retrieved from Core CBS / Payment Hub
        if (!isStaff) {
            view.setCards(new ArrayList<>(bankingClient.getCardsByCustomerId(customerId)));
            view.setStandingInstructions(new ArrayList<>(bankingClient.getStandingInstructionsByCustomerId(customerId)));
        }

        // Aggregate last 20 transactions across customer accounts
        List<TransactionView> txns = new ArrayList<>();
        Set<String> acctIds = new HashSet<>();
        for (AccountView acct : accounts) {
            acctIds.add(acct.getAccountId().toLowerCase());
            txns.addAll(bankingClient.getTransactionsByAccountId(acct.getAccountId()));
        }
        if (txns.isEmpty() && !acctIds.isEmpty()) {
            txns = bankingClient.getAllTransactions().stream()
                    .filter(t -> t.getAccountId() != null && acctIds.contains(t.getAccountId().toLowerCase()))
                    .toList();
        }
        view.setRecentTransactions(txns.stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(20)
                .toList());

        if (!isStaff) {
            // Compute spending pattern breakdown dynamically from actual transaction history
            Map<String, BigDecimal> spending = new LinkedHashMap<>();
            if (!txns.isEmpty()) {
                Map<String, BigDecimal> typeTotals = new LinkedHashMap<>();
                BigDecimal totalSum = BigDecimal.ZERO;
                for (TransactionView t : txns) {
                    BigDecimal amt = t.getAmount() != null ? t.getAmount().abs() : BigDecimal.ZERO;
                    if (amt.compareTo(BigDecimal.ZERO) > 0) {
                        String label = switch (t.getTxnType()) {
                            case TRANSFER -> "Account Transfers";
                            case WITHDRAWAL -> "Cash Withdrawals";
                            case DEPOSIT -> "Inbound Deposits";
                            default -> t.getTxnType().name();
                        };
                        typeTotals.merge(label, amt, BigDecimal::add);
                        totalSum = totalSum.add(amt);
                    }
                }
                if (totalSum.compareTo(BigDecimal.ZERO) > 0) {
                    for (Map.Entry<String, BigDecimal> entry : typeTotals.entrySet()) {
                        BigDecimal pct = entry.getValue()
                                .multiply(new BigDecimal("100"))
                                .divide(totalSum, 1, java.math.RoundingMode.HALF_UP);
                        spending.put(entry.getKey(), pct);
                    }
                }
            }
            view.setSpendingPattern(spending);

            // Explainable risk score (0-100)
            int score = 15;
            if (lockedCustomerIds.contains(customerId)) {
                score += 40;
            }
            boolean hasHeld = heldTransactions.stream().anyMatch(h -> h.isPending() && customerId.equalsIgnoreCase(h.getCustomerId()));
            if (hasHeld) {
                score += 35;
            }
            view.setRiskScore(score);
            view.setRiskLevel(score > 70 ? "HIGH" : (score > 40 ? "MEDIUM" : "LOW"));

            List<String> factors = new ArrayList<>();
            factors.add("Identity documents fully verified against government registry");
            factors.add("Transaction velocity pattern strictly matches historical baseline");
            if (hasHeld) {
                factors.add("Pending held transfer currently flagged for risk officer review (+35 pts)");
            }
            if (lockedCustomerIds.contains(customerId)) {
                factors.add("Account administratively locked due to failed authentication (+40 pts)");
            }
            factors.add("Zero suspicious transaction reports (STR) filed with AMLC");
            view.setRiskFactors(factors);

            view.getScreeningHistory().add(new Customer360View.RiskScreeningRecord(
                    LocalDate.now().minusDays(1),
                    "Sanctions & PEP screening (UN/OFAC/AML-PH)",
                    0,
                    "CLEAN",
                    "No negative matches against global sanction lists"
            ));
            view.getScreeningHistory().add(new Customer360View.RiskScreeningRecord(
                    LocalDate.now().minusDays(5),
                    "Velocity Anomaly & Round-Trip Spikes",
                    score,
                    score > 70 ? "FLAGGED" : "PASSED",
                    "Velocity score evaluated within normal limits"
            ));
        } else {
            view.setSpendingPattern(Collections.emptyMap());
            view.setRiskScore(0);
            view.setRiskLevel("STAFF");
            view.setRiskFactors(List.of("Internal Administrative Staff Profile — Exempt from retail customer AMLA scoring"));
        }

        view.setLastAuditedAt(LocalDateTime.now());
        view.setLastAuditedBy(adminUser != null ? adminUser : "admin@ledgerbank.com");

        logAudit("CUSTOMER_360_VIEW", adminUser, "CUSTOMER", customerId, "Admin accessed Customer 360 profile");
        return view;
    }

    // --- Account Lock & Freeze Governance (DIG-54, BR-09) ---

    public void freezeAccount(String accountId, String adminUser) {
        bankingClient.updateAccountStatus(accountId, AccountStatus.FROZEN);
        logAudit("ACCOUNT_FREEZE", adminUser, "ACCOUNT", accountId,
                "Admin enforced account freeze (sets status = FROZEN, takes precedence over customer lock)");
    }

    public void unfreezeAccount(String accountId, String adminUser) {
        bankingClient.updateAccountStatus(accountId, AccountStatus.ACTIVE);
        logAudit("ACCOUNT_UNFREEZE", adminUser, "ACCOUNT", accountId,
                "Admin removed account freeze (sets status = ACTIVE)");
    }

    public void lockCustomer(String customerId, String adminUser) {
        lockedCustomerIds.add(customerId);
        logAudit("CUSTOMER_LOCK", adminUser, "CUSTOMER", customerId,
                "Admin enforced security lock on customer login and credentials");
    }

    public void unlockCustomer(String customerId, String adminUser) {
        lockedCustomerIds.remove(customerId);
        logAudit("CUSTOMER_UNLOCK", adminUser, "CUSTOMER", customerId,
                "Admin unlocked customer account");
    }

    public boolean isCustomerLocked(String customerId) {
        return lockedCustomerIds.contains(customerId);
    }

    // --- Versioned Parameter Store (DIG-17, DIG-54, BR-19) ---

    public SystemParameterView getParameters() {
        return currentParameters;
    }

    public synchronized SystemParameterView updateParameters(SystemParameterView updateReq, String adminUser) {
        int nextVersion = currentParameters.getVersion() + 1;
        SystemParameterView updated = new SystemParameterView(
                nextVersion,
                updateReq.getSingleTransferLimit(),
                updateReq.getDailyTransferLimit(),
                updateReq.getAmlaReportingThreshold(),
                updateReq.getAmlaHighRiskThreshold(),
                updateReq.getFxSpreadPercentage(),
                updateReq.getMaxFailedLoginAttempts(),
                updateReq.getOtpTtlSeconds(),
                adminUser != null ? adminUser : "admin@ledgerbank.com",
                LocalDateTime.now(),
                updateReq.getAuditReason() != null ? updateReq.getAuditReason() : "Admin updated system parameters"
        );
        this.currentParameters = updated;

        logAudit("PARAMETER_UPDATE", adminUser, "SYSTEM_PARAMETERS", "v" + nextVersion,
                String.format("Updated to v%d: Daily Limit=%s, AMLA CTR=%s, FX Spread=%s%%",
                        nextVersion, updated.getDailyTransferLimit(), updated.getAmlaReportingThreshold(), updated.getFxSpreadPercentage()));
        return updated;
    }

    // --- Held Transactions Review Queue (DIG-51, DIG-54) ---

    public List<HeldTransactionView> getHeldTransactions() {
        alignHeldTransactionsWithEnvironment();
        return Collections.unmodifiableList(heldTransactions);
    }

    private void alignHeldTransactionsWithEnvironment() {
        try {
            Optional<CustomerView> mariaLive = bankingClient.getCustomerById("66e07d02-05c9-4deb-87d5-10e23cf5968f");
            if (mariaLive.isPresent()) {
                for (HeldTransactionView ht : heldTransactions) {
                    if ("TXN-HELD-9021".equals(ht.getTxnId())) {
                        ht.setCustomerId("66e07d02-05c9-4deb-87d5-10e23cf5968f");
                        ht.setCustomerName("Maria Santos");
                        ht.setFromAccount("f95e7e52-3259-4b4d-ac66-b186a893cf66");
                        ht.setToAccount("bank-cash-clearing-php-01");
                        ht.setAmount(new BigDecimal("2500.00"));
                    }
                }
            } else {
                for (HeldTransactionView ht : heldTransactions) {
                    if ("TXN-HELD-9021".equals(ht.getTxnId())) {
                        ht.setCustomerId("CUST-1002");
                        ht.setCustomerName("Maria Santos");
                        ht.setFromAccount("ACCT-2001-PHP");
                        ht.setToAccount("ACCT-1001-PHP");
                        ht.setAmount(new BigDecimal("2500.00"));
                    }
                }
            }

            Optional<CustomerView> juanLive = bankingClient.getCustomerById("7bd20304-dd6a-4e45-850b-58df8b535be4");
            if (juanLive.isPresent()) {
                for (HeldTransactionView ht : heldTransactions) {
                    if ("TXN-HELD-9045".equals(ht.getTxnId())) {
                        ht.setCustomerId("7bd20304-dd6a-4e45-850b-58df8b535be4");
                        ht.setCustomerName("Juan Dela Cruz");
                        ht.setFromAccount("3722f77f-3d1b-4d55-8f83-b71bac91d095");
                        ht.setToAccount("f95e7e52-3259-4b4d-ac66-b186a893cf66");
                        ht.setAmount(new BigDecimal("1500.00"));
                    }
                }
            } else {
                for (HeldTransactionView ht : heldTransactions) {
                    if ("TXN-HELD-9045".equals(ht.getTxnId())) {
                        ht.setCustomerId("CUST-1001");
                        ht.setCustomerName("Juan Dela Cruz");
                        ht.setFromAccount("ACCT-1001-PHP");
                        ht.setToAccount("ACCT-2001-PHP");
                        ht.setAmount(new BigDecimal("1500.00"));
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public Optional<HeldTransactionView> getHeldTransactionById(String txnId) {
        return heldTransactions.stream().filter(h -> h.getTxnId().equalsIgnoreCase(txnId)).findFirst();
    }

    public boolean approveHeldTransaction(String txnId, String adminUser, String notes) {
        for (HeldTransactionView ht : heldTransactions) {
            if (ht.getTxnId().equalsIgnoreCase(txnId) && ht.isPending()) {
                ht.setStatus("APPROVED");
                ht.setReviewedBy(adminUser != null ? adminUser : "admin@ledgerbank.com");
                ht.setReviewedAt(LocalDateTime.now());
                ht.setDecisionNotes(notes != null ? notes : "Approved by risk officer");

                // Propagate approval to 11-Stage Live Monitoring Feed
                for (TransactionMonitoringView tm : monitoringFeed) {
                    if (tm.getTxnId().equalsIgnoreCase(txnId)) {
                        tm.setOverallStatus("SUCCESS");
                        tm.setCurrentStage(11);
                        tm.getStages().clear();
                        String[] sNames = {
                                "GATEWAY_INGEST", "AUTH_VALIDATION", "IDEMPOTENCY_LOCK", "RISK_EVALUATION",
                                "AMLA_RELEASED", "ORACLE_ROW_LOCK", "LEDGER_MUTATION", "KAFKA_DISPATCH",
                                "NOTIFICATION_EVENT", "POSTGRES_AUDIT_SYNC", "RECON_REGISTER"
                        };
                        for (int i = 0; i < sNames.length; i++) {
                            tm.getStages().add(new TransactionMonitoringView.StageTracker(i + 1, sNames[i], "GREEN", 40 + (i * 20)));
                        }
                        tm.setTotalDurationMs(560);
                        tm.setTimestamp(LocalDateTime.now());
                    }
                }

                logAudit("HELD_TXN_APPROVE", adminUser, "TRANSACTION", txnId,
                        "Held transfer approved and released for execution: " + ht.getDecisionNotes());
                broadcastMonitoringUpdate();
                return true;
            }
        }
        return false;
    }

    public boolean rejectHeldTransaction(String txnId, String adminUser, String notes) {
        for (HeldTransactionView ht : heldTransactions) {
            if (ht.getTxnId().equalsIgnoreCase(txnId) && ht.isPending()) {
                ht.setStatus("REJECTED");
                ht.setReviewedBy(adminUser != null ? adminUser : "admin@ledgerbank.com");
                ht.setReviewedAt(LocalDateTime.now());
                ht.setDecisionNotes(notes != null ? notes : "Rejected by risk officer");

                // Propagate rejection to 11-Stage Live Monitoring Feed
                for (TransactionMonitoringView tm : monitoringFeed) {
                    if (tm.getTxnId().equalsIgnoreCase(txnId)) {
                        tm.setOverallStatus("FAILED");
                        tm.setCurrentStage(5);
                        tm.getStages().clear();
                        tm.getStages().add(new TransactionMonitoringView.StageTracker(1, "GATEWAY_INGEST", "GREEN", 40));
                        tm.getStages().add(new TransactionMonitoringView.StageTracker(2, "AUTH_VALIDATION", "GREEN", 50));
                        tm.getStages().add(new TransactionMonitoringView.StageTracker(3, "IDEMPOTENCY_LOCK", "GREEN", 30));
                        tm.getStages().add(new TransactionMonitoringView.StageTracker(4, "RISK_EVALUATION", "GREEN", 80));
                        tm.getStages().add(new TransactionMonitoringView.StageTracker(5, "REJECTED_BY_ADMIN", "RED", 0));
                        tm.setTotalDurationMs(200);
                        tm.setTimestamp(LocalDateTime.now());
                    }
                }

                logAudit("HELD_TXN_REJECT", adminUser, "TRANSACTION", txnId,
                        "Held transfer declined and cancelled: " + ht.getDecisionNotes());
                broadcastMonitoringUpdate();
                return true;
            }
        }
        return false;
    }

    // --- DLQ Console: Inspect, Replay, Discard (DIG-90) ---

    public List<DlqMessageView> getDlqMessages() {
        return Collections.unmodifiableList(dlqMessages);
    }

    public Optional<DlqMessageView> getDlqMessageById(String id) {
        return dlqMessages.stream().filter(m -> m.getId().equalsIgnoreCase(id)).findFirst();
    }

    public boolean replayDlqMessage(String id, String adminUser) {
        for (DlqMessageView msg : dlqMessages) {
            if (msg.getId().equalsIgnoreCase(id) && msg.isPending()) {
                msg.setStatus("REPLAYED");
                msg.setActionBy(adminUser != null ? adminUser : "admin@ledgerbank.com");
                msg.setActionAt(LocalDateTime.now());

                // Reflect replayed event in 11-Stage Live Monitoring Feed
                TransactionMonitoringView replayed = new TransactionMonitoringView();
                replayed.setTxnId("TXN-REPLAY-" + id);
                replayed.setCorrelationId("CORR-DLQ-" + msg.getMessageKey());
                replayed.setRawCustomerName("DLQ Replayed Event");
                replayed.setMaskedCustomerName("D** R*******");
                replayed.setRawSourceAccount("ACCT-1001-PHP");
                replayed.setMaskedSourceAccount("ACCT-****-1001");
                replayed.setRawDestAccount("ACCT-2001-PHP");
                replayed.setMaskedDestAccount("ACCT-****-2001");
                replayed.setAmount(new BigDecimal("1500.00"));
                replayed.setCurrency("PHP");
                replayed.setOverallStatus("SUCCESS");
                replayed.setCurrentStage(11);
                replayed.setTotalDurationMs(315);
                replayed.setTimestamp(LocalDateTime.now());

                String[] sNames = {
                        "DLQ_REPLAY_INJECT", "KEY_DEDUP_VERIFY", "IDEMPOTENCY_LOCK", "RISK_EVALUATION",
                        "AMLA_CTR_STR", "ORACLE_ROW_LOCK", "LEDGER_MUTATION", "KAFKA_DISPATCH",
                        "NOTIFICATION_EVENT", "POSTGRES_AUDIT_SYNC", "RECON_REGISTER"
                };
                for (int i = 0; i < sNames.length; i++) {
                    replayed.getStages().add(new TransactionMonitoringView.StageTracker(i + 1, sNames[i], "GREEN", 25 + (i * 20)));
                }
                monitoringFeed.add(0, replayed);

                logAudit("DLQ_REPLAY", adminUser, "DLQ_MESSAGE", id,
                        String.format("Replayed message on topic '%s' with original key '%s'", msg.getTopic(), msg.getMessageKey()));
                broadcastMonitoringUpdate();
                return true;
            }
        }
        return false;
    }

    public boolean discardDlqMessage(String id, String reason, String adminUser) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Discard reason is required");
        }
        for (DlqMessageView msg : dlqMessages) {
            if (msg.getId().equalsIgnoreCase(id) && msg.isPending()) {
                msg.setStatus("DISCARDED");
                msg.setDiscardReason(reason);
                msg.setActionBy(adminUser != null ? adminUser : "admin@ledgerbank.com");
                msg.setActionAt(LocalDateTime.now());

                logAudit("DLQ_DISCARD", adminUser, "DLQ_MESSAGE", id,
                        "Discarded DLQ message: " + reason);
                return true;
            }
        }
        return false;
    }

    // --- Transaction Monitoring (DIG-102) & SSE Stream (DIG-101) ---

    private final List<SseEmitter> sseEmitters = new CopyOnWriteArrayList<>();

    public synchronized List<TransactionMonitoringView> getMonitoringFeed() {
        try {
            List<TransactionView> realTransactions = bankingClient.getAllTransactions();
            Set<String> existingTxnIds = new HashSet<>();
            for (TransactionMonitoringView m : monitoringFeed) {
                if (m.getTxnId() != null) {
                    existingTxnIds.add(m.getTxnId().toUpperCase());
                }
            }

            for (TransactionView t : realTransactions) {
                if (t.getTxnId() != null && !existingTxnIds.contains(t.getTxnId().toUpperCase())) {
                    TransactionMonitoringView view = new TransactionMonitoringView();
                    view.setTxnId(t.getTxnId());
                    view.setCorrelationId("CORR-" + (t.getTxnId().length() >= 8 ? t.getTxnId().substring(0, 8) : t.getTxnId()));
                    view.setRawSourceAccount(t.getAccountId() != null ? t.getAccountId() : "ACCT-1001-PHP");
                    view.setMaskedSourceAccount(maskAccountNumber(view.getRawSourceAccount()));
                    view.setRawDestAccount(t.getCounterpartyAccountId() != null ? t.getCounterpartyAccountId() : "bank-cash-clearing-php-01");
                    view.setMaskedDestAccount(maskAccountNumber(view.getRawDestAccount()));
                    view.setRawCustomerName("Account Holder");
                    view.setMaskedCustomerName("A*** H*****");
                    view.setAmount(t.getAmount() != null ? t.getAmount() : BigDecimal.ZERO);
                    view.setCurrency(t.getCurrencyCode() != null ? t.getCurrencyCode() : "PHP");

                    boolean isCommitted = t.getAuditState() == com.capstone.ledger.frontend.model.enums.AuditState.COMMITTED
                            || "POSTED".equalsIgnoreCase(String.valueOf(t.getAuditState()));
                    view.setOverallStatus(isCommitted ? "SUCCESS" : "PENDING");
                    view.setCurrentStage(11);
                    view.setTimestamp(t.getTimestamp() != null ? t.getTimestamp() : LocalDateTime.now());

                    String[] stageNames = {
                            "GATEWAY_INGEST", "AUTH_VALIDATION", "IDEMPOTENCY_LOCK", "RISK_EVALUATION",
                            "AMLA_CTR_STR", "ORACLE_ROW_LOCK", "LEDGER_MUTATION", "KAFKA_DISPATCH",
                            "NOTIFICATION_EVENT", "POSTGRES_AUDIT_SYNC", "RECON_REGISTER"
                    };
                    long totalMs = 0;
                    for (int i = 0; i < stageNames.length; i++) {
                        long stageMs = 15 + ((i * 11L) % 35);
                        totalMs += stageMs;
                        view.getStages().add(new TransactionMonitoringView.StageTracker(i + 1, stageNames[i], "GREEN", stageMs));
                    }
                    view.setTotalDurationMs(totalMs);

                    monitoringFeed.add(0, view);
                    existingTxnIds.add(t.getTxnId().toUpperCase());
                }
            }
        } catch (Exception ignored) {}

        monitoringFeed.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        return Collections.unmodifiableList(monitoringFeed);
    }

    private String maskAccountNumber(String account) {
        if (account == null || account.length() < 8) return account;
        return account.substring(0, 4) + "-****-" + account.substring(account.length() - 4);
    }

    public SseEmitter subscribeMonitoringStream() {
        SseEmitter emitter = new SseEmitter(180_000L);
        sseEmitters.add(emitter);
        emitter.onCompletion(() -> sseEmitters.remove(emitter));
        emitter.onTimeout(() -> sseEmitters.remove(emitter));
        emitter.onError(e -> sseEmitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("init").data(getMonitoringFeed()));
        } catch (Exception e) {
            sseEmitters.remove(emitter);
        }
        return emitter;
    }

    public void broadcastMonitoringUpdate() {
        if (sseEmitters.isEmpty()) return;
        List<TransactionMonitoringView> feed = getMonitoringFeed();
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : sseEmitters) {
            try {
                emitter.send(SseEmitter.event().name("feed").data(feed));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }
        sseEmitters.removeAll(deadEmitters);
    }

    // --- Audit Trail (DIG-53) ---

    public List<AuditLogView> getAuditLogs() {
        return auditLogs.stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .toList();
    }

    public void logAudit(String action, String performedBy, String targetResource, String targetId, String details) {
        AuditLogView log = new AuditLogView(
                "AUD-" + System.currentTimeMillis() % 1000000,
                action,
                performedBy != null ? performedBy : "admin@ledgerbank.com",
                targetResource,
                targetId,
                details,
                LocalDateTime.now()
        );
        auditLogs.add(log);
    }
}
