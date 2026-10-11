param(
    [Parameter(Mandatory = $true)]
    [string]$SshPrivateKeyPath,

    [string]$SubscriptionId = 'b82a736e-f4ca-4443-a538-9aa8de016682',
    [string]$ResourceGroupName = 'rg-banking-demo-centralus',
    [string]$AksClusterName = ''
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repositoryRoot

function Invoke-Az {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & az @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Azure CLI command failed: az $($Arguments -join ' ')"
    }
    return $output
}

function Invoke-Kubectl {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & kubectl @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl command failed: kubectl $($Arguments -join ' ')"
    }
    return $output
}

function Get-DeploymentOutput {
    param(
        [Parameter(Mandatory = $true)]$Outputs,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $property = $Outputs.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value.value) {
        throw "Required Bicep output '$Name' was not found."
    }
    return [string]$property.Value.value
}

function Get-KeyVaultSecret {
    param(
        [Parameter(Mandatory = $true)][string]$Vault,
        [Parameter(Mandatory = $true)][string]$Name
    )
    return Invoke-Az @(
        'keyvault', 'secret', 'show',
        '--vault-name', $Vault,
        '--name', $Name,
        '--query', 'value',
        '--output', 'tsv'
    )
}

function New-DeploymentSecret {
    $bytes = [byte[]]::new(36)
    $randomNumberGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomNumberGenerator.GetBytes($bytes)
    }
    finally {
        $randomNumberGenerator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')
}

function Write-RestrictedSecretFile {
    param([Parameter(Mandatory = $true)][string]$Value)
    $path = Join-Path ([IO.Path]::GetTempPath()) ("banking-secret-{0}.txt" -f [guid]::NewGuid())
    [IO.File]::WriteAllText($path, $Value, [Text.UTF8Encoding]::new($false))
    $currentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $path /inheritance:r /grant:r "*$($currentUserSid):(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Remove-Item -LiteralPath $path -Force
        throw "Could not restrict access to the temporary secret file (icacls exit code $LASTEXITCODE)."
    }
    return $path
}

try {
    Invoke-Az @('account', 'set', '--subscription', $SubscriptionId) | Out-Null
    $deployment = Invoke-Az @(
        'deployment', 'group', 'show',
        '--resource-group', $ResourceGroupName,
        '--name', 'banking-demo',
        '--query', 'properties.outputs',
        '--output', 'json'
    ) | ConvertFrom-Json

    $resolvedAksName = $AksClusterName
    if ([string]::IsNullOrWhiteSpace($resolvedAksName)) {
        $resolvedAksName = Get-DeploymentOutput $deployment 'aksName'
    }
    $vaultName = Get-DeploymentOutput $deployment 'keyVaultName'
    $postgresHost = Get-DeploymentOutput $deployment 'postgresHostName'
    $oracleHost = Get-DeploymentOutput $deployment 'oracleVmPrivateIp'
    $oraclePublicIp = Get-DeploymentOutput $deployment 'oracleVmPublicIp'
    $resolvedPrivateKey = (Resolve-Path -LiteralPath $SshPrivateKeyPath).Path
    $sshCommand = Get-Command ssh -ErrorAction Stop
    $scpCommand = Get-Command scp -ErrorAction Stop
    $secretNames = @{
        POSTGRES_ADMIN_USER = Get-DeploymentOutput $deployment 'postgresAdminUser'
        POSTGRES_ADMIN_PASSWORD = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'postgresAdminSecretName')
        POSTGRES_PASSWORD = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'ledgerSecretName')
        CHANNEL_DB_PASSWORD = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'channelSecretName')
        ORACLE_PASSWORD = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'oracleAppSecretName')
        JWT_SECRET = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'jwtSecretName')
        GRAFANA_ADMIN_PASSWORD = Get-KeyVaultSecret $vaultName (Get-DeploymentOutput $deployment 'grafanaVaultEntry')
        REDIS_USERNAME = 'default'
        REDIS_PASSWORD = New-DeploymentSecret
    }

    $secretNames.ORACLE_USERNAME = 'ledger_app'
    $secretNames.CHANNEL_DB_USERNAME = 'channel_user'

    $oracleSqlFiles = @(
        (Resolve-Path -LiteralPath 'sql/oracle/01_oracle_main_db.sql').Path
        (Resolve-Path -LiteralPath 'sql/oracle/02_seed_data_oracle.sql').Path
    )
    & $scpCommand.Source -i $resolvedPrivateKey -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new @oracleSqlFiles "azureuser@${oraclePublicIp}:/tmp/"
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to copy the Oracle initialization and seed scripts to the Oracle VM.'
    }
    $oracleInitCommand = 'sudo install -d -o root -g root -m 0755 /opt/oracle/initdb && sudo install -o root -g root -m 0644 /tmp/01_oracle_main_db.sql /opt/oracle/initdb/01_oracle_main_db.sql && sudo install -o root -g root -m 0644 /tmp/02_seed_data_oracle.sql /opt/oracle/initdb/02_seed_data_oracle.sql && sudo chown 54321:54321 /opt/oracle/oradata && sudo chmod 0750 /opt/oracle/oradata && sudo sed -i s#/dev/disk/azure/scsi1/lun0#/dev/disk/azure/data/by-lun/0#g /usr/local/bin/start-oracle-xe && sudo sh -c ''status=$(docker inspect --format "{{.State.Status}}" oracle-db 2>/dev/null || true); health=$(docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{end}}" oracle-db 2>/dev/null || true); if [ "$status" = restarting ] || [ "$health" = unhealthy ]; then docker rm -f oracle-db; fi'' && sudo systemctl reset-failed oracle-xe.service && sudo systemctl restart oracle-xe.service'
    & $sshCommand.Source -i $resolvedPrivateKey -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new "azureuser@$oraclePublicIp" $oracleInitCommand
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to stage the Oracle initialization scripts or start the Oracle container.'
    }

    $oracleReady = $false
    $oracleProbeOutput = ''
    for ($attempt = 1; $attempt -le 90; $attempt++) {
        $oracleProbeOutput = (& $sshCommand.Source -i $resolvedPrivateKey -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new "azureuser@$oraclePublicIp" 'sudo docker exec oracle-db healthcheck.sh' 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0) {
            $oracleReady = $true
            break
        }
        if ($attempt % 6 -eq 0) {
            Write-Output "Oracle initialization is still running (attempt $attempt/90)."
        }
        Start-Sleep -Seconds 10
    }
    if (-not $oracleReady) {
        $oracleDiagnostics = (& $sshCommand.Source -i $resolvedPrivateKey -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new "azureuser@$oraclePublicIp" 'sudo systemctl --no-pager --full status oracle-xe.service; sudo docker logs --tail 80 oracle-db 2>&1 || true' 2>$null | Out-String).Trim()
        Write-Output $oracleDiagnostics
        if (-not [string]::IsNullOrWhiteSpace($oracleProbeOutput)) {
            Write-Output "Last Oracle health-check output: $oracleProbeOutput"
        }
        throw 'Oracle did not become healthy within 15 minutes. Review the service/container diagnostics above before retrying.'
    }

    if (-not (Get-Command kubelogin -ErrorAction SilentlyContinue)) {
        $toolDirectory = Join-Path $env:USERPROFILE 'bin'
        New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
        $kubeloginPath = Join-Path $toolDirectory 'kubelogin.exe'
        Invoke-Az @(
            'aks', 'install-cli',
            '--kubelogin-install-location', $kubeloginPath
        ) | Out-Null
        $env:PATH = "$toolDirectory;$env:PATH"
    }
    if (-not (Get-Command kubelogin -ErrorAction SilentlyContinue)) {
        throw 'kubelogin is required for the AKS Microsoft Entra login. Install it with az aks install-cli and retry.'
    }

    Invoke-Az @(
        'aks', 'get-credentials',
        '--resource-group', $ResourceGroupName,
        '--name', $resolvedAksName,
        '--admin',
        '--overwrite-existing'
    ) | Out-Null

    Invoke-Kubectl @('apply', '-f', 'k8s/base/namespace.yaml') | Out-Null
    Invoke-Kubectl @('apply', '-f', 'k8s/bootstrap/promtail-rbac.yaml') | Out-Null
    $secretManifest = @{
        apiVersion = 'v1'
        kind = 'Secret'
        metadata = @{
            name = 'banking-app-secrets'
            namespace = 'banking'
        }
        type = 'Opaque'
        stringData = $secretNames
    } | ConvertTo-Json -Depth 8
    $secretFile = Write-RestrictedSecretFile $secretManifest
    try {
        Invoke-Kubectl @('apply', '-f', $secretFile) | Out-Null
    }
    finally {
        Remove-Item -LiteralPath $secretFile -Force
    }

    $config = Get-Content -Raw -LiteralPath 'k8s/bootstrap/banking-config.json.tpl'
    $config = $config.Replace('__ORACLE_HOST__', $oracleHost)
    $config = $config.Replace('__POSTGRES_HOST__', $postgresHost)
    $config = $config.Replace('__REDIS_HOST__', 'redis')
    $config = $config.Replace('__REDIS_PORT__', '6379')
    $configFile = Join-Path ([IO.Path]::GetTempPath()) ("banking-config-{0}.json" -f [guid]::NewGuid())
    [IO.File]::WriteAllText($configFile, $config, [Text.UTF8Encoding]::new($false))
    try {
        Invoke-Kubectl @('apply', '-f', $configFile) | Out-Null
    }
    finally {
        Remove-Item -LiteralPath $configFile -Force
    }
    Invoke-Kubectl @('apply', '-f', 'k8s/base/promtail-config.yaml') | Out-Null

    $seedConfig = Invoke-Kubectl @(
        'create', 'configmap', 'postgres-seed-files',
        '--namespace', 'banking',
        '--from-file=01_postgres_audit_db.sql=sql/postgres/01_postgres_audit_db.sql',
        '--from-file=02_seed_data_postgres.sql=sql/postgres/02_seed_data_postgres.sql',
        '--from-file=01_postgres_channel_db.sql=sql/channel/01_postgres_channel_db.sql',
        '--from-file=02_seed_data_channel.sql=sql/channel/02_seed_data_channel.sql',
        '--dry-run=client',
        '--output', 'yaml'
    )
    $seedConfig | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply the PostgreSQL seed ConfigMap.'
    }

    $existingSeedJob = Invoke-Kubectl @(
        'get', 'job', 'postgres-seed',
        '--namespace', 'banking',
        '--ignore-not-found',
        '--output', 'json'
    )
    if (-not [string]::IsNullOrWhiteSpace(($existingSeedJob -join ''))) {
        $existingSeedJobStatus = ($existingSeedJob | ConvertFrom-Json).status
        if ([int]$existingSeedJobStatus.failed -gt 0 -and [int]$existingSeedJobStatus.succeeded -eq 0) {
            Invoke-Kubectl @(
                'delete', 'job', 'postgres-seed',
                '--namespace', 'banking',
                '--wait=true'
            ) | Out-Null
            $existingSeedJob = ''
        }
    }
    if ([string]::IsNullOrWhiteSpace(($existingSeedJob -join ''))) {
        Invoke-Kubectl @('apply', '-f', 'k8s/bootstrap/postgres-seed-job.yaml') | Out-Null
    }

    $seedDeadline = [DateTime]::UtcNow.AddMinutes(15)
    while ($true) {
        $seedJobStatus = Invoke-Kubectl @(
            'get', 'job', 'postgres-seed',
            '--namespace', 'banking',
            '--output', 'json'
        ) | ConvertFrom-Json
        if ([int]$seedJobStatus.status.succeeded -gt 0) {
            break
        }
        if ([int]$seedJobStatus.status.failed -gt 0) {
            $seedJobLogs = & kubectl logs --namespace banking job/postgres-seed 2>&1 | Out-String
            Write-Output $seedJobLogs
            throw 'The managed PostgreSQL schema/seed job failed. Review the job logs above before retrying.'
        }
        if ([DateTime]::UtcNow -ge $seedDeadline) {
            throw 'The managed PostgreSQL schema/seed job did not complete within 15 minutes.'
        }
        Start-Sleep -Seconds 10
    }

    $datasourceConfig = Invoke-Kubectl @(
        'create', 'configmap', 'grafana-datasources',
        '--namespace', 'banking',
        '--from-file=grafana/provisioning/datasources',
        '--dry-run=client',
        '--output', 'yaml'
    )
    $datasourceConfig | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply Grafana datasource configuration.'
    }

    $dashboardConfig = Invoke-Kubectl @(
        'create', 'configmap', 'grafana-dashboards',
        '--namespace', 'banking',
        '--from-file=grafana/provisioning/dashboards',
        '--dry-run=client',
        '--output', 'yaml'
    )
    $dashboardConfig | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply Grafana dashboards.'
    }

    $prometheusConfig = Invoke-Kubectl @(
        'create', 'configmap', 'prometheus-config',
        '--namespace', 'banking',
        '--from-file=prometheus/prometheus.yml',
        '--from-file=prometheus/alert_rules.yml',
        '--from-file=prometheus/recording_rules.yml',
        '--dry-run=client',
        '--output', 'yaml'
    )
    $prometheusConfig | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply Prometheus configuration.'
    }

    $lokiConfig = Invoke-Kubectl @(
        'create', 'configmap', 'loki-config',
        '--namespace', 'banking',
        '--from-file=loki/loki-config.yml',
        '--dry-run=client',
        '--output', 'yaml'
    )
    $lokiConfig | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to apply Loki configuration.'
    }

    Write-Output 'Oracle and PostgreSQL schema/seed data are installed. Secrets and observability configuration are in the banking namespace.'
    Write-Output 'Next, configure GitHub Environment production and Docker Hub credentials, then push to main to deploy the application images.'
}
finally {
    Pop-Location
}
