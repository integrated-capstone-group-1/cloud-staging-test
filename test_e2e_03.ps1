# E2E-03 Test Script: Smart Shortfall Multi-Currency Transfer
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained JWT Token: $($token.Substring(0, 20))..."

Write-Host "`n=== STEP 2: Fetch conversion quote (PHP -> EUR for 50.00 EUR) ==="
$quote = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=EUR&amount=50.00" -Method Get -Headers @{
    "Authorization" = "Bearer $token"
}

Write-Host "Quote Received:"
$quote | ConvertTo-Json -Depth 5

$quoteId = if ($quote.quote_id) { $quote.quote_id } elseif ($quote.data.quote_id) { $quote.data.quote_id } else { $quote.id }
Write-Host "Extracted Quote ID: $quoteId"

Write-Host "`n=== STEP 3 & 4: Submit Shortfall Payment (€50.00 funded by PHP pocket) ==="
$idempKey = [System.Guid]::NewGuid().ToString()
$payBody = @{
    recipient_account_id = "acct-maria-eur-01"
    amount = 50.0000
    currency = "EUR"
    funding_pocket_id = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
    quote_id = $quoteId
} | ConvertTo-Json

try {
    $payRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Method Post -Headers @{
        "Authorization" = "Bearer $token"
        "X-Idempotency-Key" = $idempKey
    } -ContentType "application/json" -Body $payBody

    Write-Host "Payment Response:"
    $payRes | ConvertTo-Json -Depth 5
    $txnId = $payRes.transaction_id
    Write-Host "Transaction ID: $txnId"
} catch {
    Write-Host "Payment failed: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Host "Body: $($reader.ReadToEnd())"
    }
}

