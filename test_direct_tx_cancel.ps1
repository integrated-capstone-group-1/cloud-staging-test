$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token

Write-Host "Running wget from inside orchestrator-service container..."
docker compose exec -T orchestrator-service wget -S -O- --header "Authorization: Bearer $token" --post-data "x=1" "http://transaction-service:8084/api/v1/payments/test-get/cancel"

