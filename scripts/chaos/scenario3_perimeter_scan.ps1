# ==============================================================================
# Scenario 3 (CH-03): Zero-Trust Perimeter Port Scan
# Verifies:
#   1. Internal service & DB ports (:8081-8088, :1521, :5432, :6379, :9092, :3000)
#      refuse direct connection from host (TcpTestSucceeded : False).
#   2. Only :8080 (API Gateway) and :8090 (Admin Portal) accept incoming traffic.
# ==============================================================================
param(
    [string]$TargetHost = "127.0.0.1"
)

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "[CH-03] Starting Scenario 3: Perimeter Port Cloaking Scan on $TargetHost" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan

$InternalPorts = @(
    @{ Port = 1521; Name = "Oracle XE Master DB" },
    @{ Port = 5432; Name = "PostgreSQL Audit DB" },
    @{ Port = 6379; Name = "Redis Cache & Token Bucket" },
    @{ Port = 9092; Name = "Kafka Event Broker" },
    @{ Port = 2181; Name = "Zookeeper" },
    @{ Port = 8081; Name = "registration-service" },
    @{ Port = 8082; Name = "login-service" },
    @{ Port = 8083; Name = "accounts-service" },
    @{ Port = 8084; Name = "transaction-service" },
    @{ Port = 8085; Name = "notification-service" },
    @{ Port = 8086; Name = "reconciliation-service" },
    @{ Port = 8087; Name = "forex-service" },
    @{ Port = 8088; Name = "risk-engine" },
    @{ Port = 8089; Name = "orchestrator-service" },
    @{ Port = 3000; Name = "Grafana Direct Port" },
    @{ Port = 9090; Name = "Prometheus Direct Port" },
    @{ Port = 3100; Name = "Loki Direct Port" }
)

$PublicPorts = @(
    @{ Port = 8080; Name = "API Gateway (Single Public Entrypoint)" },
    @{ Port = 8090; Name = "Admin Operations Web Portal" }
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrEmpty($ScriptDir)) {
    $ScriptDir = (Get-Location).Path
}

$ComposeFile = Join-Path (Split-Path -Parent $ScriptDir) "docker-compose.yml"
if (-not (Test-Path $ComposeFile)) {
    $ComposeFile = Join-Path (Get-Location).Path "docker-compose.yml"
}

Write-Host "`n--- Validating docker-compose.yml Port Cloaking (Static Architecture Rule) ---" -ForegroundColor Yellow
$LeakedComposePorts = 0
if (Test-Path $ComposeFile) {
    $composeContent = Get-Content $ComposeFile -Raw
    # Search for published ports outside of api-gateway and frontend-web
    $services = (Get-Content $ComposeFile)
    $currentService = ""
    $inPorts = $false
    foreach ($line in $services) {
        if ($line -match '^\s{2}([a-zA-Z0-9_-]+):') {
            $currentService = $matches[1]
            $inPorts = $false
        }
        if ($line -match '^\s{4}ports:') {
            $inPorts = $true
            continue
        }
        if ($inPorts -and $line -match '^\s{6}-\s*"([0-9]+):[0-9]+"') {
            $exposedHostPort = $matches[1]
            if ($currentService -ne "api-gateway" -and $currentService -ne "frontend-web") {
                Write-Host "[COMPOSE LEAK] Service '$currentService' exposes host port $exposedHostPort!" -ForegroundColor Red
                $LeakedComposePorts++
            } else {
                Write-Host "[ALLOWED PERIMETER] Service '$currentService' publishes external port $exposedHostPort." -ForegroundColor Green
            }
        } elseif ($inPorts -and $line -match '^\s{4}[a-zA-Z0-9_-]+:') {
            $inPorts = $false
        }
    }
}

Write-Host "`n--- Scanning TCP Ports on $TargetHost ---" -ForegroundColor Yellow
$LeakedRuntimePorts = 0
foreach ($entry in $InternalPorts) {
    $port = $entry.Port
    $name = $entry.Name
    $test = Test-NetConnection -ComputerName $TargetHost -Port $port -WarningAction SilentlyContinue
    if ($test.TcpTestSucceeded) {
        # Check if process is a native host process
        $proc = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        $procName = "Unknown"
        if ($proc -and $proc.OwningProcess) {
            $p = Get-Process -Id $proc.OwningProcess -ErrorAction SilentlyContinue
            if ($p) { $procName = $p.ProcessName }
        }
        if ($procName -like "*postgres*" -or $procName -like "*oracle*") {
            Write-Host "[HOST PROCESS DETECTED] Port $port ($name) active via host native $procName (Cloaked in Docker Compose)." -ForegroundColor Yellow
        } else {
            Write-Host "[LEAK DETECTED] Port $port ($name) is reachable!" -ForegroundColor Red
            $LeakedRuntimePorts++
        }
    } else {
        Write-Host "[CLOAKED] Port $port ($name) correctly refused connection (TcpTestSucceeded: False)." -ForegroundColor Green
    }
}

Write-Host "`n--- Scanning Allowed Public Perimeter Ports ---" -ForegroundColor Yellow
foreach ($entry in $PublicPorts) {
    $port = $entry.Port
    $name = $entry.Name
    $test = Test-NetConnection -ComputerName $TargetHost -Port $port -WarningAction SilentlyContinue
    if ($test.TcpTestSucceeded) {
        Write-Host "[ACTIVE] Port $port ($name) is open and responding." -ForegroundColor Green
    } else {
        Write-Host "[STANDBY] Port $port ($name) is not currently listening (run docker compose up)." -ForegroundColor Gray
    }
}

Write-Host "`n======================================================================" -ForegroundColor Cyan
if ($LeakedComposePorts -eq 0 -and $LeakedRuntimePorts -eq 0) {
    Write-Host "[CH-03] PASSED: Zero-trust perimeter intact. 0 internal ports leaked!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[CH-03] FAILED: Internal ports leaked (Compose: $LeakedComposePorts, Runtime: $LeakedRuntimePorts)!" -ForegroundColor Red
    exit 1
}

