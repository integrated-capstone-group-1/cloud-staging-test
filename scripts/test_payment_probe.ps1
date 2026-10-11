$ErrorActionPreference = "Stop"

$loginBody = '{"email":"juan.delacruz@example.com","password":"password123"}'
$loginRes = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body $loginBody
$token = $loginRes.data.token
$customerId = $loginRes.data.customerId
Write-Host "Logged in as: $($loginRes.data.email) (Customer ID: $customerId)"
Write-Host "Token acquired: $($token.Substring(0, 25))..."

$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}

Write-Host "`n--- 1. Testing GET /api/v1/accounts/{id}/pockets (BFF) ---"
$pockets = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/accounts/3722f77f-3d1b-4d55-8f83-b71bac91d095/pockets" -Headers $headers -Method Get
Write-Host "Pockets returned successfully:"
$pockets | ForEach-Object { Write-Host " - Pocket: $($_.currencyCode) | Balance: $($_.balanceAmount) | AccountId: $($_.accountId)" }

Write-Host "`n--- 2. Testing POST /api/v1/payments (Initiation -> Risk Screening) ---"
$idempKey = [System.Guid]::NewGuid().ToString()
$headers["X-Idempotency-Key"] = $idempKey

$paymentBody = @{
    sender_account_id = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
    recipient_account_id = "f95e7e52-3259-4b4d-ac66-b186a893cf66"
    amount = 50.00
    currency = "PHP"
} | ConvertTo-Json

$payRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Headers $headers -Method Post -Body $paymentBody
Write-Host "Payment response:" ($payRes | ConvertTo-Json -Depth 5)

$txnId = $payRes.transaction_id

Write-Host "`n--- 3. Testing GET /api/v1/payments/{id} (Status Tracking) ---"
$statusRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/$txnId" -Headers $headers -Method Get
Write-Host "Status lookup response:" ($statusRes | ConvertTo-Json -Depth 5)

Write-Host "`n--- 4. Testing GET /api/v1/payments/history (Audit & History) ---"
$historyRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/history" -Headers $headers -Method Get
Write-Host "History count: $($historyRes.Count)"
$historyRes | Select-Object -First 3 | ForEach-Object {
    Write-Host " - Txn: $($_.transaction_id) | Status: $($_.status) | Stage: $($_.stage) | Amount: $($_.paid_amount) $($_.paid_currency)"
}

Write-Host "`n--- 5. Testing Idempotency Replay (Re-POST same key) ---"
$replayRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Headers $headers -Method Post -Body $paymentBody
Write-Host "Idempotency replay response:" ($replayRes | ConvertTo-Json -Depth 5)
Write-Host "Replayed transaction ID match: $(if ($replayRes.transaction_id -eq $txnId) { 'MATCH (VERIFIED)' } else { 'MISMATCH' })"
