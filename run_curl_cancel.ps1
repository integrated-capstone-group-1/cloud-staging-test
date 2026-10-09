$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token

$url = "http://localhost:8080/api/v1/payments/txn-cas-test-01/cancel?reason=UserRequestedUndo"
Write-Host "Calling $url via curl.exe"

& curl.exe -i -X POST $url -H "Authorization: Bearer $token" -H "Content-Length: 0"

