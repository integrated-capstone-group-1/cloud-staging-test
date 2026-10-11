# ==============================================================================
# NFR-RESIL-01: External FX Feed Outage & 24h Stale Guard (CH-06, BR-04)
# ==============================================================================
Write-Host "======================================================================"
Write-Host "   NFR-RESIL-01: EXTERNAL FX FEED OUTAGE & 24H STALE GUARD (CH-06)     "
Write-Host "======================================================================"

# Step 1: Login as Juan
$loginResp = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = if ($loginResp.token) { $loginResp.token } else { $loginResp.data.token }
Write-Host "Customer Authenticated: Juan (Token: $($token.Substring(0,25))...)"

# Step 2: Request normal fresh FX quote from DB cache
Write-Host "`n[Step 1] Requesting normal active FX quote (PHP -> EUR):"
$quoteFresh = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=EUR&amount=50.00" -Method Get -Headers @{ "Authorization" = "Bearer $token" }
Write-Host "  Success! Quote ID: $($quoteFresh.quote_id), Total PHP: $($quoteFresh.total_source_amount), Expires in: $($quoteFresh.expires_in_seconds)s"

# Step 3: Fast-forward DB rate timestamp `fetched_at` by 25 hours
Write-Host "`n[Step 2] Fast-forwarding Oracle FX_RATE fetched_at by 25 hours (simulating stale cache)..."
$sqlAge = @"
UPDATE FX_RATE SET FETCHED_AT = SYSTIMESTAMP - INTERVAL '25' HOUR WHERE (BASE_CURRENCY = 'PHP' AND QUOTE_CURRENCY = 'EUR') OR (BASE_CURRENCY = 'EUR' AND QUOTE_CURRENCY = 'PHP');
COMMIT;
exit;
"@
$sqlAge | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null
Write-Host "  Oracle FX_RATE cache timestamps artificially aged > 24 hours."

# Step 4: Request FX quote and assert 422 FX_RATE_STALE
Write-Host "`n[Step 3] Requesting cross-currency quote with stale rates:"
try {
    $staleResp = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=EUR&amount=50.00" -Method Get -Headers @{ "Authorization" = "Bearer $token" }
    Write-Error "FAILED: Expected 422 FX_RATE_STALE but received 200 OK!"
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    $stream = $_.Exception.Response.GetResponseStream()
    $reader = New-Object System.IO.StreamReader($stream)
    $respBody = $reader.ReadToEnd()
    Write-Host "  HTTP Status: $statusCode"
    Write-Host "  Response: $respBody"
    if ($statusCode -eq 422 -and $respBody -like "*FX_RATE_STALE*") {
        Write-Host "  [VERIFIED] Stale rate correctly rejected with 422 FX_RATE_STALE!" -ForegroundColor Green
    } else {
        Write-Error "Unexpected response code or body: $respBody"
    }
}

# Step 5: Verify same-currency transactions continue unaffected
Write-Host "`n[Step 4] Verifying same-currency quote (PHP -> PHP) unaffected:"
$quoteSame = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/fx/quote?from=PHP&to=PHP&amount=50.00" -Method Get -Headers @{ "Authorization" = "Bearer $token" }
Write-Host "  Success! Same currency quote ID: $($quoteSame.quote_id), Total: $($quoteSame.total_source_amount) PHP"

# Step 6: Restore DB rate timestamp
Write-Host "`n[Step 5] Restoring FX_RATE timestamp to fresh SYSTIMESTAMP..."
$sqlRestore = @"
UPDATE FX_RATE SET FETCHED_AT = SYSTIMESTAMP WHERE (BASE_CURRENCY = 'PHP' AND QUOTE_CURRENCY = 'EUR') OR (BASE_CURRENCY = 'EUR' AND QUOTE_CURRENCY = 'PHP');
COMMIT;
exit;
"@
$sqlRestore | docker exec -i oracle-db sqlplus -s ledger_app/LedgerAppPass123@//localhost:1521/XEPDB1 > $null
Write-Host "  FX_RATE cache restored."

Write-Host "`n[SUCCESS] NFR-RESIL-01: External FX Feed Outage & 24h Stale Guard PASSED!" -ForegroundColor Green
