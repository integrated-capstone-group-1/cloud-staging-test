$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token
Write-Host "Obtained token: $($token.Substring(0, 15))..."

$payload = '{"recipient_account_id":"f95e7e52-3259-4b4d-ac66-b186a893cf66","amount":100.00,"currency":"PHP"}'

docker run --rm --network banking-backend-monorepo_banking-net curlimages/curl:latest -i -X POST http://transaction-service:8084/api/v1/payments -H "Content-Type: application/json" -H "Authorization: Bearer $token" -H "X-Idempotency-Key: test-direct-debug-2" -d "$payload"

