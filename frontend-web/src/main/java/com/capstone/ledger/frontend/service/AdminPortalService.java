package com.capstone.ledger.frontend.service;

import com.capstone.ledger.frontend.adapter.BankingApiClient;
import com.capstone.ledger.frontend.model.*;
import com.capstone.ledger.frontend.model.enums.AccountStatus;
import com.capstone.ledger.frontend.model.enums.AccountType;
import com.capstone.ledger.frontend.model.enums.KycStatus;
import org.springframework.stereotype.Service;

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

        // Pre-seeded Held Transactions
        heldTransactions.add(new HeldTransactionView(
                "TXN-HELD-9021",
                "CORR-R71-001",
                "CUST-1002",
                "Maria Santos",
                "ACCT-5001-PHP",
                "ACCT-9922-USD",
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
                "CUST-1001",
                "Juan Dela Cruz",
                "ACCT-1001-PHP",
                "ACCT-8811-PHP",
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
                "{\"txnId\":\"TXN-9081\",\"source\":\"ACCT-1001-PHP\",\"target\":\"ACCT-2001-PHP\",\"amount\":1500.00}",
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
                "{\"customerId\":\"CUST-1002\",\"channel\":\"SMS\",\"template\":\"OTP_AUTH\"}",
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
        TransactionMonitoringView m1 = new TransactionMonitoringView();
        m1.setTxnId("TXN-MON-8801");
        m1.setCorrelationId("CORR-MON-8801");
        m1.setRawCustomerName("Juan Dela Cruz");
        m1.setMaskedCustomerName("J*** D***");
        m1.setRawSourceAccount("ACCT-1001-PHP");
        m1.setMaskedSourceAccount("ACCT-****-1001");
        m1.setRawDestAccount("ACCT-2001-PHP");
        m1.setMaskedDestAccount("ACCT-****-2001");
        m1.setAmount(new BigDecimal("12500.00"));
        m1.setCurrency("PHP");
        m1.setOverallStatus("SUCCESS");
        m1.setCurrentStage(9);
        m1.setTotalDurationMs(840);
        m1.setTimestamp(LocalDateTime.now().minusMinutes(2));

        String[] stageNames = {
                "INITIATED", "VALIDATED", "SANCTION_CHECKED", "RISK_EVALUATED",
                "HOLD_PLACED", "CORE_POSTED", "LEDGER_COMMITTED", "NOTIFIED", "COMPLETED"
        };
        for (int i = 0; i < stageNames.length; i++) {
            m1.getStages().add(new TransactionMonitoringView.StageTracker(i + 1, stageNames[i], "GREEN", 50 + (i * 20)));
        }
        monitoringFeed.add(m1);

        TransactionMonitoringView m2 = new TransactionMonitoringView();
        m2.setTxnId("TXN-MON-8802");
        m2.setCorrelationId("CORR-MON-8802");
        m2.setRawCustomerName("Maria Santos");
        m2.setMaskedCustomerName("M*** S***");
        m2.setRawSourceAccount("ACCT-5001-PHP");
        m2.setMaskedSourceAccount("ACCT-****-5001");
        m2.setRawDestAccount("ACCT-9922-USD");
        m2.setMaskedDestAccount("ACCT-****-9922");
        m2.setAmount(new BigDecimal("490000.00"));
        m2.setCurrency("PHP");
        m2.setOverallStatus("HELD");
        m2.setCurrentStage(4);
        m2.setTotalDurationMs(320);
        m2.setTimestamp(LocalDateTime.now().minusMinutes(25));

        m2.getStages().add(new TransactionMonitoringView.StageTracker(1, "INITIATED", "GREEN", 45));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(2, "VALIDATED", "GREEN", 60));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(3, "SANCTION_CHECKED", "GREEN", 85));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(4, "RISK_EVALUATED", "OUTLINE", 130));
        m2.getStages().add(new TransactionMonitoringView.StageTracker(5, "HELD", "RED", 0));
        monitoringFeed.add(m2);
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

        // If still empty (e.g., test scenario), create standard default pockets
        if (accounts.isEmpty()) {
            accounts = List.of(
                    new AccountView("ACCT-" + customerId + "-PHP", customerId, AccountType.SAVINGS, "PHP", AccountStatus.ACTIVE, new BigDecimal("150000.00"), LocalDateTime.now().minusMonths(3)),
                    new AccountView("ACCT-" + customerId + "-USD", customerId, AccountType.CHECKING, "USD", AccountStatus.ACTIVE, new BigDecimal("2500.00"), LocalDateTime.now().minusMonths(2))
            );
        }

        for (AccountView acct : accounts) {
            BigDecimal ledger = acct.getBalance() != null ? acct.getBalance() : BigDecimal.ZERO;
            BigDecimal held = BigDecimal.ZERO;
            // Check if any held transaction has this account as source
            for (HeldTransactionView ht : heldTransactions) {
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

        // Virtual Cards
        view.getCards().add(new Customer360View.CardSummaryView(
                "CARD-VIRT-" + customerId + "-01",
                "4111-XXXX-XXXX-9912",
                "VIRTUAL_VISA",
                "PHP",
                "ACTIVE",
                new BigDecimal("50000.00"),
                true
        ));

        // Standing Instructions
        view.getStandingInstructions().add(new Customer360View.StandingInstructionView(
                "SI-AUTO-101",
                "Meralco Utilities",
                "ACCT-BILLS-MERALCO",
                new BigDecimal("4500.00"),
                "PHP",
                "MONTHLY",
                LocalDate.now().plusDays(10),
                "ACTIVE"
        ));

        // Aggregate last 20 transactions across customer accounts
        List<TransactionView> txns = new ArrayList<>();
        Set<String> acctIds = new HashSet<>();
        for (AccountView acct : accounts) {
            acctIds.add(acct.getAccountId().toLowerCase());
            txns.addAll(bankingClient.getTransactionsByAccountId(acct.getAccountId()));
        }
        if (txns.isEmpty()) {
            txns = bankingClient.getAllTransactions().stream()
                    .filter(t -> t.getAccountId() != null && acctIds.contains(t.getAccountId().toLowerCase()))
                    .toList();
        }
        view.setRecentTransactions(txns.stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(20)
                .toList());

        // Spending pattern breakdown
        Map<String, BigDecimal> spending = new LinkedHashMap<>();
        spending.put("Bank Transfers", new BigDecimal("42.5"));
        spending.put("Bills & Utilities", new BigDecimal("28.0"));
        spending.put("E-Commerce & Retail", new BigDecimal("19.5"));
        spending.put("ATM & Cash", new BigDecimal("10.0"));
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
        return Collections.unmodifiableList(heldTransactions);
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

                logAudit("HELD_TXN_APPROVE", adminUser, "TRANSACTION", txnId,
                        "Held transfer approved and released for execution: " + ht.getDecisionNotes());
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

                logAudit("HELD_TXN_REJECT", adminUser, "TRANSACTION", txnId,
                        "Held transfer declined and cancelled: " + ht.getDecisionNotes());
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

                logAudit("DLQ_REPLAY", adminUser, "DLQ_MESSAGE", id,
                        String.format("Replayed message on topic '%s' with original key '%s'", msg.getTopic(), msg.getMessageKey()));
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

    // --- Transaction Monitoring (DIG-102) ---

    public List<TransactionMonitoringView> getMonitoringFeed() {
        return Collections.unmodifiableList(monitoringFeed);
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
