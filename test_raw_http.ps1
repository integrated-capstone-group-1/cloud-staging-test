$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$t = $login.data.token

$rawHttp = "POST /api/v1/payments/txn-cas-test-01/cancel?reason=UserRequestedUndo HTTP/1.1`r`nHost: localhost:8084`r`nAuthorization: Bearer $t`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
$rawHttp | docker exec -i transaction-service nc localhost 8084

