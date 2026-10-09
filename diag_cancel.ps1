$login = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method Post -ContentType "application/json" -Body '{"email":"juan.delacruz@example.com","password":"password123"}'
$token = $login.data.token

$url = "http://localhost:8080/api/v1/payments/txn-cas-test-01/cancel?reason=User%20requested%20undo"
Write-Host "Target: $url"

$wr = [System.Net.HttpWebRequest]::Create($url)
$wr.Method = "POST"
$wr.Headers.Add("Authorization", "Bearer " + $token)
$wr.ContentLength = 0

try {
    $resp = $wr.GetResponse()
    $status = [int]$resp.StatusCode
    Write-Host "HTTP Status: $status"
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
    $body = $reader.ReadToEnd()
    Write-Host "Response: $body"
} catch [System.Net.WebException] {
    $status = [int]$_.Exception.Response.StatusCode
    Write-Host "Caught Status: $status"
    $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
    $body = $reader.ReadToEnd()
    Write-Host "Body: $body"
}

