# E2E-04 Test Script: 7-Status CAS Lifecycle & Undo Cancellation
$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained JWT Token: $($token.Substring(0, 20))..."

Write-Host "`n=== SCENARIO A: Cancel AUTHORIZED transaction within undo window ==="
try {
    $cancelRes = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments/txn-cas-test-02/cancel?reason=User%20requested%20undo" -Method Post -Headers @{
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
