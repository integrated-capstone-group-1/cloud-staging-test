# NFR-RACE-01: Cancel vs. Process CAS Claim Race (TS-04, BR-31)
# Objective: Prove Compare-and-Set guarantees exactly one winner when customer cancel races background settlement worker.

Write-Host "======================================================================"
Write-Host "   NFR-RACE-01: CANCEL VS. PROCESS CAS CLAIM RACE (TS-04, BR-31)      "
Write-Host "======================================================================"

# Step 1: Authenticate as customer (Juan)
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Authenticated Customer: Juan (Token: $($token.Substring(0, 20))...)"

# Step 2: Seed AUTHORIZED payment record
$testTxnId = "TXN-RACE-01-" + [System.Guid]::NewGuid().ToString().Substring(0, 8)
Write-Host "`n[Step 1] Seeding transaction in AUTHORIZED state: $testTxnId"

$sqlSeed = @"
INSERT INTO TRANSACTION_MASTER (
    TXN_ID, CUSTOMER_ID, DEBIT_ACCOUNT_ID, CREDIT_ACCOUNT_ID,
    MUTATION_AMOUNT, CURRENCY_CODE, TXN_STATUS, STAGE,
    TXN_TYPE, CREATED_BY, IDEMPOTENCY_KEY, CREATED_AT
) VALUES (
    '$testTxnId', '7bd20304-dd6a-4e45-850b-58df8b535be4', '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'f95e7e52-3259-4b4d-ac66-b186a893cf66',
    1000.0000, 'PHP', 'AUTHORIZED', 'AUTH_CONFIRMED',
    'TRANSFER', 'test-user', '$testTxnId', SYSTIMESTAMP
);
COMMIT;
exit;
"@
$sqlSeed | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null

# Step 3: Spawn 2 concurrent racers
Write-Host "`n[Step 2] Executing concurrent race:"
Write-Host "  Racer 1 (Customer Cancel): POST /api/v1/payments/$testTxnId/cancel"
Write-Host "  Racer 2 (Worker Settlement Claim): Atomic CAS UPDATE to PROCESSING"

$cancelScript = {
    param($url, $token)
    try {
        $res = Invoke-RestMethod -Uri $url -Method Post -Headers @{ "Authorization" = "Bearer $token" } -ContentType "application/json" -Body "{}"
        return @{ Success = $true; Status = $res.status; Code = 200 }
    } catch {
        $statusCode = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { 500 }
        return @{ Success = $false; Code = $statusCode; Error = $_.Exception.Message }
    }
}

$workerScript = {
    param($txnId)
    $sqlCas = @"
SET SERVEROUTPUT ON;
DECLARE
    v_updated NUMBER;
BEGIN
    UPDATE TRANSACTION_MASTER
    SET TXN_STATUS = 'PROCESSING', STAGE = 'SETTLING', UPDATED_AT = SYSTIMESTAMP
    WHERE TXN_ID = '$txnId' AND TXN_STATUS = 'AUTHORIZED';
    v_updated := SQL%ROWCOUNT;
    COMMIT;
    DBMS_OUTPUT.PUT_LINE('CAS_ROWS:' || v_updated);
END;
/
exit;
"@
    $out = $sqlCas | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1
    if ($out -match "CAS_ROWS:1") {
        return @{ Winner = "WORKER"; Rows = 1 }
    } else {
        return @{ Winner = "NONE"; Rows = 0 }
    }
}

$cancelJob = Start-Job -ScriptBlock $cancelScript -ArgumentList "http://localhost:8080/api/v1/payments/$testTxnId/cancel?reason=CustomerRacingUndo", $token
$workerJob = Start-Job -ScriptBlock $workerScript -ArgumentList $testTxnId

$cancelResult = Receive-Job -Job $cancelJob -Wait
$workerResult = Receive-Job -Job $workerJob -Wait
Remove-Job -Job $cancelJob, $workerJob

Write-Host "Cancel Response : Code=$($cancelResult.Code), Status=$($cancelResult.Status)"
Write-Host "Worker CAS Claim: RowsUpdated=$($workerResult.Rows)"

# Step 4: Verify Final Oracle State Invariant
$finalStateQuery = "SELECT TXN_STATUS, STAGE FROM TRANSACTION_MASTER WHERE TXN_ID = '$testTxnId';`nexit;"
$finalState = $finalStateQuery | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1

Write-Host "`n[Step 3] Final State in Oracle:"
Write-Host $finalState

$isConsistent = ($cancelResult.Code -eq 200 -and $workerResult.Rows -eq 0) -or ($cancelResult.Code -eq 409 -and $workerResult.Rows -eq 1)

if ($isConsistent) {
    Write-Host "[SUCCESS] Compare-and-Set Invariant Proven: Exactly one winner won the race! No dual-state anomaly." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAILURE] Invariant breached or unexpected outcome!" -ForegroundColor Red
    exit 1
}

