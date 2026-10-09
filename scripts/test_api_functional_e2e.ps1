<#
.SYNOPSIS
    Automated Black-Box API Functional Test Suite for Phase 3 E2E Verification
    Target: Edge API Gateway (http://localhost:8080)
    Branch: test/pk-api-test
#>

$ErrorActionPreference = "Continue"
$GatewayBase = "http://localhost:8080"
$Results = [System.Collections.Generic.List[PSCustomObject]]::new()
$StartTime = Get-Date

function Record-TestResult {
    param(
        [string]$SuiteId,
        [string]$TestId,
        [string]$Description,
        [int]$ExpectedStatus,
        [int]$ActualStatus,
        [bool]$Passed,
        [long]$DurationMs,
        [string]$Details = ""
    )
    $obj = [PSCustomObject]@{
        SuiteId        = $SuiteId
        TestId         = $TestId
        Description    = $Description
        ExpectedStatus = $ExpectedStatus
        ActualStatus   = $ActualStatus
        Passed         = $Passed
        DurationMs     = $DurationMs
        Details        = $Details
    }
    $Results.Add($obj)

    $badge = if ($Passed) { "[PASS]" } else { "[FAIL]" }
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host "$badge $TestId : $Description (HTTP $ActualStatus in ${DurationMs}ms)" -ForegroundColor $color
    if (-not $Passed -and $Details) {
        Write-Host "       Error: $Details" -ForegroundColor Yellow
    }
}

function Invoke-ApiCall {
    param(
        [string]$Uri,
        [string]$Method = "GET",
        [hashtable]$Headers = @{},
        [string]$Body = $null
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = 0
    $respBody = $null
    $errorMsg = $null

    try {
        $params = @{
            Uri         = $Uri
            Method      = $Method
            Headers     = $Headers
            ErrorAction = "Stop"
        }
        if ($Body) {
            $params["Body"] = $Body
            $params["ContentType"] = "application/json"
        }
        $response = Invoke-RestMethod @params
        $sw.Stop()
        $statusCode = 200 # Invoke-RestMethod treats 2xx as success
        return @{
            StatusCode = $statusCode
            Body       = $response
            DurationMs = $sw.ElapsedMilliseconds
            Error      = $null
        }
    }
    catch {
        $sw.Stop()
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        else {
            $statusCode = 500
        }
        $errorMsg = $_.Exception.Message
        return @{
            StatusCode = $statusCode
            Body       = $null
            DurationMs = $sw.ElapsedMilliseconds
            Error      = $errorMsg
        }
    }
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "   PHASE 3: AUTOMATED BLACK-BOX API FUNCTIONAL TEST SUITE (GATEWAY)   " -ForegroundColor Cyan
Write-Host "   Target: $GatewayBase | Monorepo Branch: test/pk-api-test           " -ForegroundColor Cyan
Write-Host "======================================================================`n" -ForegroundColor Cyan

# -----------------------------------------------------------------------------
# SUITE 1: Identity, Authentication & JWT Lifecycle (J-01 / API-PK-01)
# -----------------------------------------------------------------------------
Write-Host "=== Suite 1: Identity, Authentication & JWT Lifecycle ===" -ForegroundColor Magenta

# 1.1: Register New Customer
$uniqueEmail = "pk.test." + [System.Guid]::NewGuid().ToString().Substring(0, 8) + "@example.com"
$regPayload = @{
    firstName = "Test"
    lastName  = "User"
    email     = $uniqueEmail
    contactNo = "+639171234567"
    birthDate = "1995-05-15"
    password  = "Password123!"
} | ConvertTo-Json

$res11 = Invoke-ApiCall -Uri "$GatewayBase/api/auth/register" -Method "POST" -Body $regPayload
$pass11 = ($res11.StatusCode -eq 200 -or $res11.StatusCode -eq 201)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.1" -Description "Register new customer via Gateway" `
    -ExpectedStatus 200 -ActualStatus $res11.StatusCode -Passed $pass11 -DurationMs $res11.DurationMs

# 1.2: Duplicate Registration Rejection
$res12 = Invoke-ApiCall -Uri "$GatewayBase/api/auth/register" -Method "POST" -Body $regPayload
$pass12 = ($res12.StatusCode -eq 409 -or $res12.StatusCode -eq 400)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.2" -Description "Reject duplicate customer registration" `
    -ExpectedStatus 409 -ActualStatus $res12.StatusCode -Passed $pass12 -DurationMs $res12.DurationMs

# 1.3: Customer Login (Juan Dela Cruz)
$juanLogin = @{ email = "juan.delacruz@example.com"; password = "password123" } | ConvertTo-Json
$res13 = Invoke-ApiCall -Uri "$GatewayBase/api/auth/login" -Method "POST" -Body $juanLogin
$juanToken = $null
$juanCustId = $null
if ($res13.Body -and $res13.Body.data) {
    $juanToken = $res13.Body.data.token
    $juanCustId = $res13.Body.data.customerId
}
$pass13 = ($res13.StatusCode -eq 200 -and $juanToken -ne $null)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.3" -Description "Authenticate Customer (Juan) & retrieve JWT" `
    -ExpectedStatus 200 -ActualStatus $res13.StatusCode -Passed $pass13 -DurationMs $res13.DurationMs

# 1.4: Admin Login
$adminLogin = @{ email = "admin@ledgerbank.com"; password = "admin123" } | ConvertTo-Json
$res14 = Invoke-ApiCall -Uri "$GatewayBase/api/auth/login" -Method "POST" -Body $adminLogin
$adminToken = $null
if ($res14.Body -and $res14.Body.data) {
    $adminToken = $res14.Body.data.token
}
$pass14 = ($res14.StatusCode -eq 200 -and $adminToken -ne $null)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.4" -Description "Authenticate Admin & retrieve JWT with ROLE_ADMIN" `
    -ExpectedStatus 200 -ActualStatus $res14.StatusCode -Passed $pass14 -DurationMs $res14.DurationMs

# 1.5: Temp User Login & Logout (Blacklist Verification)
$tempLogin = @{ email = "maria.santos@example.com"; password = "password123" } | ConvertTo-Json
$resTempLogin = Invoke-ApiCall -Uri "$GatewayBase/api/auth/login" -Method "POST" -Body $tempLogin
$tempToken = if ($resTempLogin.Body -and $resTempLogin.Body.data) { $resTempLogin.Body.data.token } else { $null }

$res15 = Invoke-ApiCall -Uri "$GatewayBase/api/auth/logout" -Method "POST" -Headers @{ "Authorization" = "Bearer $tempToken" }
$pass15 = ($res15.StatusCode -eq 200 -and $tempToken -ne $null)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.5" -Description "Logout customer & write blacklist to Redis" `
    -ExpectedStatus 200 -ActualStatus $res15.StatusCode -Passed $pass15 -DurationMs $res15.DurationMs

# 1.6: Revoked Token Blocked at Gateway Edge (Protected Route Probe)
$res16 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments/history" -Method "GET" -Headers @{ "Authorization" = "Bearer $tempToken" }
$pass16 = ($res16.StatusCode -eq 401)
Record-TestResult -SuiteId "S1" -TestId "API-PK-01.6" -Description "Block revoked/blacklisted token at Gateway edge (TOKEN_REVOKED)" `
    -ExpectedStatus 401 -ActualStatus $res16.StatusCode -Passed $pass16 -DurationMs $res16.DurationMs

# -----------------------------------------------------------------------------
# SUITE 2: Multi-Currency Pockets & Account Ownership Ingress (J-02 / API-PK-02)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 2: Multi-Currency Pockets & Boundary Security ===" -ForegroundColor Magenta

$juanPhpAccountId = "3722f77f-3d1b-4d55-8f83-b71bac91d095"
$mariaPhpAccountId = "f95e7e52-3259-4b4d-ac66-b186a893cf66"

# 2.1: Query Pockets for Juan's Account via Orchestrator BFF
$res21 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/accounts/$juanPhpAccountId/pockets" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pocketsCount = if ($res21.Body) { ($res21.Body | Measure-Object).Count } else { 0 }
$pass21 = ($res21.StatusCode -eq 200 -and $pocketsCount -ge 1)
Record-TestResult -SuiteId "S2" -TestId "API-PK-02.1" -Description "Query multi-currency pockets via Orchestrator BFF" `
    -ExpectedStatus 200 -ActualStatus $res21.StatusCode -Passed $pass21 -DurationMs $res21.DurationMs

# 2.2: Query Accounts by Customer ID
$res22 = Invoke-ApiCall -Uri "$GatewayBase/api/accounts/customer/$juanCustId" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass22 = ($res22.StatusCode -eq 200)
Record-TestResult -SuiteId "S2" -TestId "API-PK-02.2" -Description "Query customer accounts by customer UUID" `
    -ExpectedStatus 200 -ActualStatus $res22.StatusCode -Passed $pass22 -DurationMs $res22.DurationMs

# 2.3: Boundary Security: Juan attempts to access Maria's pockets
$res23 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/accounts/$mariaPhpAccountId/pockets" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass23 = ($res23.StatusCode -eq 403)
Record-TestResult -SuiteId "S2" -TestId "API-PK-02.3" -Description "Enforce account ownership boundary (cross-user probe)" `
    -ExpectedStatus 403 -ActualStatus $res23.StatusCode -Passed $pass23 -DurationMs $res23.DurationMs

# -----------------------------------------------------------------------------
# SUITE 3: Payment Ingress, Risk Screening & Idempotency (J-02, J-05 / API-PK-03)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 3: Payment Ingress, Synchronous Risk Screening & Idempotency ===" -ForegroundColor Magenta

$idempKey = [System.Guid]::NewGuid().ToString()
$payBody = @{
    sender_account_id    = $juanPhpAccountId
    recipient_account_id = $mariaPhpAccountId
    amount               = 25.00
    currency             = "PHP"
} | ConvertTo-Json

# 3.1: Submit Valid Payment
$res31 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $juanToken"; "X-Idempotency-Key" = $idempKey } -Body $payBody
$payTxnId = $null
if ($res31.Body) {
    $payTxnId = $res31.Body.transaction_id
}
$pass31 = (($res31.StatusCode -eq 200 -or $res31.StatusCode -eq 202) -and $payTxnId -ne $null)
Record-TestResult -SuiteId "S3" -TestId "API-PK-03.1" -Description "Submit payment transfer through Gateway -> Orchestrator -> Hub" `
    -ExpectedStatus 200 -ActualStatus $res31.StatusCode -Passed $pass31 -DurationMs $res31.DurationMs

# 3.2: Replay Identical Payment with Same Idempotency Key
$res32 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $juanToken"; "X-Idempotency-Key" = $idempKey } -Body $payBody
$replayTxnId = if ($res32.Body) { $res32.Body.transaction_id } else { $null }
$pass32 = (($res32.StatusCode -eq 200 -or $res32.StatusCode -eq 202) -and $replayTxnId -eq $payTxnId)
Record-TestResult -SuiteId "S3" -TestId "API-PK-03.2" -Description "Idempotency replay: Return cached transaction without re-execution" `
    -ExpectedStatus 200 -ActualStatus $res32.StatusCode -Passed $pass32 -DurationMs $res32.DurationMs

# 3.3: Idempotency Conflict (Same Key with Different Amount)
$conflictBody = @{
    sender_account_id    = $juanPhpAccountId
    recipient_account_id = $mariaPhpAccountId
    amount               = 999.00
    currency             = "PHP"
} | ConvertTo-Json
$res33 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $juanToken"; "X-Idempotency-Key" = $idempKey } -Body $conflictBody
$pass33 = ($res33.StatusCode -eq 409 -or $res33.StatusCode -eq 422 -or $res33.StatusCode -eq 400)
Record-TestResult -SuiteId "S3" -TestId "API-PK-03.3" -Description "Idempotency conflict probe (same key, altered payload)" `
    -ExpectedStatus 409 -ActualStatus $res33.StatusCode -Passed $pass33 -DurationMs $res33.DurationMs

# 3.4: Ownership Enforcement on Payment Debit (Juan debits Maria's account)
$unauthorizedDebit = @{
    sender_account_id    = $mariaPhpAccountId
    recipient_account_id = $juanPhpAccountId
    amount               = 10.00
    currency             = "PHP"
} | ConvertTo-Json
$res34 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $juanToken" } -Body $unauthorizedDebit
$pass34 = ($res34.StatusCode -eq 403)
Record-TestResult -SuiteId "S3" -TestId "API-PK-03.4" -Description "Reject payment debiting unauthorized source account" `
    -ExpectedStatus 403 -ActualStatus $res34.StatusCode -Passed $pass34 -DurationMs $res34.DurationMs

# -----------------------------------------------------------------------------
# SUITE 4: Transaction Lifecycle, Status Tracking & History (J-04, J-06 / API-PK-04)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 4: Transaction Lifecycle, Status Tracking & History ===" -ForegroundColor Magenta

# 4.1: Lookup Payment by ID
$res41 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments/$payTxnId" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass41 = ($res41.StatusCode -eq 200 -and $res41.Body.transaction_id -eq $payTxnId)
Record-TestResult -SuiteId "S4" -TestId "API-PK-04.1" -Description "Retrieve transaction status & stage by UUID" `
    -ExpectedStatus 200 -ActualStatus $res41.StatusCode -Passed $pass41 -DurationMs $res41.DurationMs

# 4.2: Retrieve Customer Transaction History
$res42 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments/history" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$historyCount = if ($res42.Body) { ($res42.Body | Measure-Object).Count } else { 0 }
$pass42 = ($res42.StatusCode -eq 200 -and $historyCount -ge 1)
Record-TestResult -SuiteId "S4" -TestId "API-PK-04.2" -Description "Retrieve customer chronological transaction history" `
    -ExpectedStatus 200 -ActualStatus $res42.StatusCode -Passed $pass42 -DurationMs $res42.DurationMs

# -----------------------------------------------------------------------------
# SUITE 5: Foreign Exchange Market Rates & Quotes (J-10 / API-PK-05)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 5: Foreign Exchange Market Rates & Quotes ===" -ForegroundColor Magenta

# 5.1: Retrieve Live FX Rates
$res51 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/forex/rates" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$ratesCount = if ($res51.Body) { ($res51.Body | Measure-Object).Count } else { 0 }
$pass51 = ($res51.StatusCode -eq 200 -and $ratesCount -ge 1)
Record-TestResult -SuiteId "S5" -TestId "API-PK-05.1" -Description "Retrieve live forex currency exchange rates" `
    -ExpectedStatus 200 -ActualStatus $res51.StatusCode -Passed $pass51 -DurationMs $res51.DurationMs

# -----------------------------------------------------------------------------
# SUITE 6: Governance, Admin Operations & Dual-DataSource Recon (J-08, J-13 / API-PK-06)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 6: Governance, Admin Operations & Dual-DataSource Recon ===" -ForegroundColor Magenta

# 6.1: Admin Query Master Accounts Registry
$res61 = Invoke-ApiCall -Uri "$GatewayBase/api/admin/accounts" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $adminToken" }
$adminAcctsCount = if ($res61.Body) { ($res61.Body | Measure-Object).Count } else { 0 }
$pass61 = ($res61.StatusCode -eq 200 -and $adminAcctsCount -ge 1)
Record-TestResult -SuiteId "S6" -TestId "API-PK-06.1" -Description "Query core accounts registry with ROLE_ADMIN credentials" `
    -ExpectedStatus 200 -ActualStatus $res61.StatusCode -Passed $pass61 -DurationMs $res61.DurationMs

# 6.2: Customer Forbidden from Admin Registry
$res62 = Invoke-ApiCall -Uri "$GatewayBase/api/admin/accounts" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass62 = ($res62.StatusCode -eq 403)
Record-TestResult -SuiteId "S6" -TestId "API-PK-06.2" -Description "Block non-admin customer from /api/admin/** endpoints" `
    -ExpectedStatus 403 -ActualStatus $res62.StatusCode -Passed $pass62 -DurationMs $res62.DurationMs

# 6.3: Admin Trigger Reconciliation Run
$reconPayload = @{
    auditDate   = (Get-Date).ToString("yyyy-MM-dd")
    initiatedBy = "ADMIN_AUTOMATION"
} | ConvertTo-Json
$res63 = Invoke-ApiCall -Uri "$GatewayBase/api/recon/runs" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $adminToken" } -Body $reconPayload
$pass63 = ($res63.StatusCode -eq 200 -or $res63.StatusCode -eq 201)
Record-TestResult -SuiteId "S6" -TestId "API-PK-06.3" -Description "Trigger dual-datasource reconciliation engine audit" `
    -ExpectedStatus 200 -ActualStatus $res63.StatusCode -Passed $pass63 -DurationMs $res63.DurationMs

# 6.4: Customer Forbidden from Admin Customer Directory
$res64 = Invoke-ApiCall -Uri "$GatewayBase/api/admin/customers" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass64 = ($res64.StatusCode -eq 403)
Record-TestResult -SuiteId "S6" -TestId "API-PK-06.4" -Description "Block customer token from admin customer directory" `
    -ExpectedStatus 403 -ActualStatus $res64.StatusCode -Passed $pass64 -DurationMs $res64.DurationMs

# -----------------------------------------------------------------------------
# SUITE 7: Error-Contract & RFC 7807 Probes (Section 6.3 / API-PK-07)
# -----------------------------------------------------------------------------
Write-Host "`n=== Suite 7: Error-Contract & RFC 7807 Probes ===" -ForegroundColor Magenta

# 7.1: Missing Authentication Header
$res71 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments/history" -Method "GET"
$pass71 = ($res71.StatusCode -eq 401)
Record-TestResult -SuiteId "S7" -TestId "API-PK-07.1" -Description "Reject unauthenticated request on protected route" `
    -ExpectedStatus 401 -ActualStatus $res71.StatusCode -Passed $pass71 -DurationMs $res71.DurationMs

# 7.2: Validation Failure: Negative Amount
$invalidPay = @{
    sender_account_id    = $juanPhpAccountId
    recipient_account_id = $mariaPhpAccountId
    amount               = -50.00
    currency             = "PHP"
} | ConvertTo-Json
$res72 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments" -Method "POST" `
    -Headers @{ "Authorization" = "Bearer $juanToken" } -Body $invalidPay
$pass72 = ($res72.StatusCode -eq 400)
Record-TestResult -SuiteId "S7" -TestId "API-PK-07.2" -Description "Validation error probe: Reject negative payment amount" `
    -ExpectedStatus 400 -ActualStatus $res72.StatusCode -Passed $pass72 -DurationMs $res72.DurationMs

# 7.3: Resource Not Found Probe
$res73 = Invoke-ApiCall -Uri "$GatewayBase/api/v1/payments/00000000-0000-0000-0000-000000000000" -Method "GET" `
    -Headers @{ "Authorization" = "Bearer $juanToken" }
$pass73 = ($res73.StatusCode -eq 404)
Record-TestResult -SuiteId "S7" -TestId "API-PK-07.3" -Description "Resource not found probe: Query non-existent payment ID" `
    -ExpectedStatus 404 -ActualStatus $res73.StatusCode -Passed $pass73 -DurationMs $res73.DurationMs

# -----------------------------------------------------------------------------
# SUMMARY & REPORT GENERATION
# -----------------------------------------------------------------------------
$EndTime = Get-Date
$TotalDuration = ($EndTime - $StartTime).TotalSeconds
$TotalTests = $Results.Count
$PassedTests = ($Results | Where-Object { $_.Passed }).Count
$FailedTests = ($Results | Where-Object { -not $_.Passed }).Count
$PassRate = [math]::Round(($PassedTests / $TotalTests) * 100, 2)

Write-Host "`n======================================================================" -ForegroundColor Cyan
Write-Host "                       TEST EXECUTION SUMMARY                         " -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " Total Tests Run : $TotalTests" -ForegroundColor White
Write-Host " Passed          : $PassedTests" -ForegroundColor Green
Write-Host " Failed          : $FailedTests" -ForegroundColor $(if ($FailedTests -gt 0) { "Red" } else { "Green" })
Write-Host " Pass Rate       : $PassRate %" -ForegroundColor $(if ($PassRate -eq 100) { "Green" } else { "Yellow" })
Write-Host " Execution Time  : $([math]::Round($TotalDuration, 2)) seconds" -ForegroundColor White
Write-Host "======================================================================`n" -ForegroundColor Cyan

# Export Results to JSON for Artifact Reporting
$ReportData = @{
    ExecutionTimestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss zzz")
    MonorepoBranch     = "test/pk-api-test"
    GatewayBaseUrl     = $GatewayBase
    TotalTests         = $TotalTests
    PassedTests        = $PassedTests
    FailedTests        = $FailedTests
    PassRate           = $PassRate
    TotalDurationSec   = [math]::Round($TotalDuration, 2)
    Results            = $Results
}
$ReportData | ConvertTo-Json -Depth 4 | Set-Content "scripts/api_test_results.json"
Write-Host "Execution evidence persisted to scripts/api_test_results.json" -ForegroundColor DarkGray
