# E2E-04 Test Script: 7-Status CAS Lifecycle & Undo Cancellation
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained JWT Token: $($token.Substring(0, 20))..."

Write-Host "`n=== SCENARIO A: Cancel AUTHORIZED transaction within undo window ==="
$testTxnId = "txn-cas-test-" + [System.Guid]::NewGuid().ToString().Substring(0, 8)
$sqlSeed = "INSERT INTO TRANSACTION_MASTER (TXN_ID, CUSTOMER_ID, DEBIT_ACCOUNT_ID, CREDIT_ACCOUNT_ID, MUTATION_AMOUNT, CURRENCY_CODE, TXN_STATUS, STAGE, TXN_TYPE, CREATED_BY, IDEMPOTENCY_KEY, CREATED_AT) VALUES ('$testTxnId', '7bd20304-dd6a-4e45-850b-58df8b535be4', '3722f77f-3d1b-4d55-8f83-b71bac91d095', 'f95e7e52-3259-4b4d-ac66-b186a893cf66', 10000, 'PHP', 'AUTHORIZED', 'AUTH_CONFIRMED', 'TRANSFER', 'test-user', '$testTxnId', SYSTIMESTAMP);`nCOMMIT;`nexit;"
$sqlSeed | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null

try {
    $cancelRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/$testTxnId/cancel?reason=User%20requested%20undo" -Method Post -Headers @{
        "Authorization" = "Bearer $token"
    } -ContentType "application/json" -Body "{}"
    Write-Host "Cancel Response:"
    $cancelRes | ConvertTo-Json
    if ($cancelRes.status -eq "CANCELLED") {
        Write-Host "[SUCCESS] Transaction cancelled successfully within undo window!"
    } else {
        Write-Host "[FAILURE] Status is $($cancelRes.status)"
    }
} catch {
    Write-Host "Cancel failed: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Host "Body: $($reader.ReadToEnd())"
    }
}

Write-Host "`n=== SCENARIO B: Attempt to cancel POSTED transaction (Expect 409 Conflict) ==="
# We know txn-072b286a-9991-41d4-b028-da6de4733d03 is POSTED
try {
    $resPosted = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/txn-072b286a-9991-41d4-b028-da6de4733d03/cancel?reason=Too%20late" -Method Post -Headers @{
        "Authorization" = "Bearer $token"
    } -ContentType "application/json" -Body "{}"
    Write-Host "[FAILURE] Unexpectedly succeeded in cancelling POSTED transaction: $($resPosted.status)"
} catch {
    Write-Host "Received expected error on late cancellation: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        Write-Host "HTTP Status: $($_.Exception.Response.StatusCode.value__)"
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $body = $reader.ReadToEnd()
        Write-Host "Body: $body"
        if ($_.Exception.Response.StatusCode.value__ -eq 409) {
            Write-Host "[SUCCESS] Correctly rejected with 409 Conflict!"
        }
    }
}
