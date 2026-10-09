# E2E-02 Test Script
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained JWT Token: $($token.Substring(0, 20))..."

$idempKey = "b5d19e68-0000-4000-8000-000000000002"
$body = @{
    recipient_account_id = "f95e7e52-3259-4b4d-ac66-b186a893cf66"
    amount = 5000.0000
    currency = "PHP"
} | ConvertTo-Json

Write-Host "`n=== STEP 2 & 3: First Payment Request ==="
$res1 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Method Post -Headers @{
    "Authorization" = "Bearer $token"
    "X-Idempotency-Key" = $idempKey
} -ContentType "application/json" -Body $body

Write-Host "Payment Response:"
$res1 | ConvertTo-Json

Write-Host "`n=== STEP 4 & 5: Idempotency Replay (Identical Key) ==="
$res2 = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Method Post -Headers @{
    "Authorization" = "Bearer $token"
    "X-Idempotency-Key" = $idempKey
} -ContentType "application/json" -Body $body

Write-Host "Replay Response:"
$res2 | ConvertTo-Json

if ($res1.transaction_id -eq $res2.transaction_id -and $res1.status -eq $res2.status) {
    Write-Host "`n[SUCCESS] E2E-02 Idempotency Replay verified! Exactly same transaction ID and status returned."
} else {
    Write-Host "`n[FAILURE] Responses do not match!"
}

