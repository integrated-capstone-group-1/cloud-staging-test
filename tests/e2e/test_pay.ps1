$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained token: $($token.Substring(0, 15))..."

try {
    $idemp = [System.Guid]::NewGuid().ToString()
    $res = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/payments" -Method Post -Headers @{
        "Authorization" = "Bearer $token"
        "X-Idempotency-Key" = $idemp
    } -ContentType "application/json" -Body '{"recipient_account_id":"f95e7e52-3259-4b4d-ac66-b186a893cf66","amount":5000.0000,"currency":"PHP"}'

    Write-Host "Response received:"
    $res | ConvertTo-Json -Depth 5
} catch {
    if ($_.Exception.Response) {
        Write-Host "Status Code: $($_.Exception.Response.StatusCode.value__)"
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $respBody = $reader.ReadToEnd()
        Write-Host "Response Body: $respBody"
    } else {
        Write-Host "Exception: $($_.Exception.Message)"
    }
}
