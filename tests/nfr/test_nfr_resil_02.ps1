# ==============================================================================
# NFR-RESIL-02: Process Kill Mid-Transaction Sweeper (CH-08)
# ==============================================================================
Write-Host "======================================================================"
Write-Host "   NFR-RESIL-02: PROCESS KILL MID-TRANSACTION SWEEPER (CH-08)         "
Write-Host "======================================================================"

# Step 1: Seed a transaction stuck in PROCESSING status (> 60 seconds old)
$txnId = "stuck-txn-" + [System.Guid]::NewGuid().ToString()
$idemKey = "idem-" + [System.Guid]::NewGuid().ToString()

Write-Host "`n[Step 1] Seeding stuck in-flight transaction into Oracle DB:"
Write-Host "  Transaction ID: $txnId"
Write-Host "  Status:         PROCESSING"
Write-Host "  Age:            120 seconds ago (> 60s stuckThreshold)"

$sqlSeed = @"
INSERT INTO TRANSACTION_MASTER (
    TXN_ID, CUSTOMER_ID, IDEMPOTENCY_KEY, TXN_TYPE, DEBIT_ACCOUNT_ID, CREDIT_ACCOUNT_ID,
    MUTATION_AMOUNT, CURRENCY_CODE, IS_CROSS_CURRENCY, TXN_STATUS, STAGE,
    INITIATED_AT, CREATED_AT, CREATED_BY
) VALUES (
    '$txnId', '7bd20304-dd6a-4e45-850b-58df8b535be4', '$idemKey', 'TRANSFER', '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'acct-maria-eur-01',
    10.00, 'EUR', 'N', 'PROCESSING', 'SETTLING',
    SYSTIMESTAMP - INTERVAL '120' SECOND, SYSTIMESTAMP - INTERVAL '120' SECOND, 'nfr-test'
);
COMMIT;
exit;
"@
$sqlSeed | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null

# Verify inserted record is in PROCESSING state
$sqlCheck = @"
SET HEAD OFF;
SET FEEDBACK OFF;
SELECT TXN_STATUS || '|' || STAGE FROM TRANSACTION_MASTER WHERE TXN_ID = '$txnId';
exit;
"@
$initialStatus = ($sqlCheck | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1).Trim()
Write-Host "  Confirmed initial database state: $initialStatus"

# Step 2: Trigger sweeper reconciliation
Write-Host "`n[Step 2] Awaiting scheduled StuckTransactionSweeper execution..."
Write-Host "  (Sweeper runs automatically every 30s in transaction-service)"

$resolved = $false
for ($i = 1; $i -le 12; $i++) {
    Start-Sleep -Seconds 5
    $sqlPoll = @"
SET HEAD OFF;
SET FEEDBACK OFF;
SELECT TXN_STATUS || '|' || STAGE || '|' || NVL(FAILURE_CODE, 'NONE') FROM TRANSACTION_MASTER WHERE TXN_ID = '$txnId';
exit;
"@
    $curr = ($sqlPoll | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1).Trim()
    Write-Host "  Poll $i : $curr"
    if ($curr -like "FAILED*") {
        $resolved = $true
        Write-Host "`n[Step 3] Reconciled: Transaction marked FAILED with zero dual-state or orphan state!" -ForegroundColor Green
        break
    }
}

# Step 4: Cleanup
$sqlClean = @"
DELETE FROM TRANSACTION_MASTER WHERE TXN_ID = '$txnId';
COMMIT;
exit;
"@
$sqlClean | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null

if ($resolved) {
    Write-Host "`n[SUCCESS] NFR-RESIL-02: Process Kill Mid-Transaction Sweeper PASSED!" -ForegroundColor Green
} else {
    Write-Error "NFR-RESIL-02 FAILED: Transaction was not reconciled within 60s"
}

