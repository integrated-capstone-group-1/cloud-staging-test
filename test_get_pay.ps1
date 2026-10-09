$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token

$url = "http://localhost:8080/api/v1/payments/test-id-123"
Write-Host "Calling GET $url"

& curl.exe -i -X GET $url -H "Authorization: Bearer $token"

