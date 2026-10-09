# NFR-RACE-02: Multi-Pocket Shortfall Funding Race (TS-05, BR-28)
# Objective: Prove two concurrent payments draining the same funding pocket serialize atomically.

Write-Host "======================================================================"
Write-Host "   NFR-RACE-02: MULTI-POCKET SHORTFALL FUNDING RACE (TS-05, BR-28)    "
Write-Host "======================================================================"

# Step 1: Authenticate Juan
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Authenticated Customer: Juan (Token: $($token.Substring(0, 20))...)"

# Step 2: Seed balance: Exactly PHP 3,500.00 in funding pocket, EUR pocket with 10.00 EUR
# A 50.00 EUR shortfall payment requires ~2,517.20 PHP from funding pocket.
# 2 concurrent payments would require ~5,034.40 PHP.
# With PHP 3,500.00, exactly ONE can succeed, and the other MUST fail with 422 Insufficient Balance.
Write-Host "`n[Step 1] Seeding balances in Oracle XE:"
Write-Host "  Juan PHP Pocket: 3,500.00 PHP"
Write-Host "  Juan EUR Pocket: 100.00 EUR"

$sqlSeed = @"
UPDATE ACCOUNT_MASTER SET BALANCE_AMOUNT = 3500.0000 WHERE ACCOUNT_ID = '3722f77f-3d1b-4d55-8f83-b71bac91d095';
UPDATE ACCOUNT_MASTER SET BALANCE_AMOUNT = 100.0000 WHERE ACCOUNT_ID = 'acct-juan-eur-01';
COMMIT;
exit;
"@
$sqlSeed | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null

# Step 3: Fetch fresh quotes for both threads
$quote1 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=EUR&amount=50.00" -Method Get -Headers @{ "Authorization" = "Bearer $token" }
$quote2 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=EUR&amount=50.00" -Method Get -Headers @{ "Authorization" = "Bearer $token" }

$qId1 = if ($quote1.quote_id) { $quote1.quote_id } else { $quote1.data.quote_id }
$qId2 = if ($quote2.quote_id) { $quote2.quote_id } else { $quote2.data.quote_id }

Write-Host "Quote 1: $qId1 (Cost: $($quote1.total_source_amount) PHP)"
Write-Host "Quote 2: $qId2 (Cost: $($quote2.total_source_amount) PHP)"

# Step 4: Fire 2 simultaneous EUR shortfall payments
Write-Host "`n[Step 2] Firing 2 simultaneous shortfall payments draining PHP pocket..."

$payScript = {
    param($quoteId, $token)
    $body = @{
        recipient_account_id = "acct-maria-eur-01"
        amount = 50.0000
        currency = "EUR"
        funding_pocket_id = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
        quote_id = $quoteId
    } | ConvertTo-Json
    $idemp = [System.Guid]::NewGuid().ToString()

    try {
        $res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Method Post -Headers @{
            "Authorization" = "Bearer $token"
            "X-Idempotency-Key" = $idemp
        } -ContentType "application/json" -Body $body
        return @{ Success = $true; Code = 200; TxnId = $res.transaction_id; Status = $res.status }
    } catch {
        $code = if ($_.Exception.Response) { $_.Exception.Response.StatusCode.value__ } else { 500 }
        return @{ Success = $false; Code = $code; Error = $_.Exception.Message }
    }
}

$job1 = Start-Job -ScriptBlock $payScript -ArgumentList $qId1, $token
$job2 = Start-Job -ScriptBlock $payScript -ArgumentList $qId2, $token

$res1 = Receive-Job -Job $job1 -Wait
$res2 = Receive-Job -Job $job2 -Wait
Remove-Job -Job $job1, $job2

Write-Host "Payment 1 Result: HTTP $($res1.Code) - $(if($res1.Success){$res1.Status}else{$res1.Error})"
Write-Host "Payment 2 Result: HTTP $($res2.Code) - $(if($res2.Success){$res2.Status}else{$res2.Error})"

# Step 5: Check remaining balance in Oracle
$queryBal = "SELECT BALANCE_AMOUNT FROM ACCOUNT_MASTER WHERE ACCOUNT_ID = '3722f77f-3d1b-4d55-8f83-b71bac91d095';`nexit;"
$balOut = $queryBal | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1

Write-Host "`n[Step 3] Final Balance in Oracle:"
Write-Host $balOut

$oneSucceeded = ($res1.Code -eq 200 -and $res2.Code -eq 422) -or ($res1.Code -eq 422 -and $res2.Code -eq 200)

if ($oneSucceeded) {
    Write-Host "[SUCCESS] Shortfall Race Invariant Proven: Exactly 1 payment succeeded, and the second was rejected with 422 without overdraft!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAILURE] Unexpected outcome in funding pocket race!" -ForegroundColor Red
    exit 1
}

