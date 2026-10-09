$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$t = $login.data.token
Write-Host "Obtained token: $($t.Substring(0, 20))..."

docker exec -i orchestrator-service wget --header="Authorization: Bearer $t" --post-data="" -S -O- "http://transaction-service:8084/api/v1/payments/txn-cas-test-01/cancel?reason=Test"

