param(
    [Parameter(Mandatory = $true)]
    [string]$SshPublicKeyPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^(\d{1,3}\.){3}\d{1,3}/\d{1,2}$')]
    [string]$SshAllowedCidr,

    [string]$SubscriptionId = 'b82a736e-f4ca-4443-a538-9aa8de016682',
    [string]$ResourceGroupName = 'rg-banking-demo-centralus',
    [string]$Location = 'centralus'
)

$ErrorActionPreference = 'Stop'

function Invoke-Az {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = & az @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Azure CLI command failed: az $($Arguments -join ' ')"
    }
    return $output
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

$resolvedKeyPath = (Resolve-Path -LiteralPath $SshPublicKeyPath).Path
$sshKey = (Get-Content -Raw -LiteralPath $resolvedKeyPath).Trim()
if (-not $sshKey.StartsWith('ssh-ed25519 ') -and -not $sshKey.StartsWith('ssh-rsa ')) {
    throw 'Provide an OpenSSH public key (ssh-ed25519 or ssh-rsa). Do not provide a private key.'
}
$cidrParts = $SshAllowedCidr.Split('/')
$parsedAddress = $null
if (-not [Net.IPAddress]::TryParse($cidrParts[0], [ref]$parsedAddress) -or
    $parsedAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
    [int]$cidrParts[1] -gt 32) {
    throw 'SshAllowedCidr must be a valid IPv4 CIDR such as 203.0.113.10/32.'
}

Invoke-Az @('account', 'set', '--subscription', $SubscriptionId) | Out-Null
$deployerObjectId = Invoke-Az @('ad', 'signed-in-user', 'show', '--query', 'id', '--output', 'tsv')
if ([string]::IsNullOrWhiteSpace($deployerObjectId)) {
    throw 'Could not resolve the signed-in Azure user object ID.'
}

$baseParameters = Get-Content -Raw -LiteralPath 'infra/main.parameters.json' | ConvertFrom-Json
$parameterValues = @{}
foreach ($parameter in $baseParameters.parameters.PSObject.Properties) {
    $parameterValues[$parameter.Name] = $parameter.Value
}
$parameterValues['location'] = @{ value = $Location }
$parameterValues['postgresAdminPassword'] = @{ value = New-DeploymentSecret }
$parameterValues['oracleSysPassword'] = @{ value = New-DeploymentSecret }
$parameterValues['oracleAppPassword'] = @{ value = New-DeploymentSecret }
$parameterValues['ledgerDatabasePassword'] = @{ value = New-DeploymentSecret }
$parameterValues['channelDatabasePassword'] = @{ value = New-DeploymentSecret }
$parameterValues['jwtSecret'] = @{ value = New-DeploymentSecret }
$parameterValues['grafanaPassword'] = @{ value = New-DeploymentSecret }
$parameterValues['adminSshPublicKey'] = @{ value = $sshKey }
$parameterValues['sshAllowedCidr'] = @{ value = $SshAllowedCidr }
$parameterValues['deployerObjectId'] = @{ value = $deployerObjectId }
$deploymentParameters = @{
    '$schema' = $baseParameters.'$schema'
    contentVersion = $baseParameters.contentVersion
    parameters = $parameterValues
}

$parameterFile = Join-Path ([IO.Path]::GetTempPath()) ("banking-azure-{0}.parameters.json" -f [guid]::NewGuid())
try {
    $parameterJson = $deploymentParameters | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText($parameterFile, $parameterJson, [Text.UTF8Encoding]::new($false))
    $currentUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $parameterFile /inheritance:r /grant:r "*$($currentUserSid):(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not restrict access to the temporary deployment parameter file (icacls exit code $LASTEXITCODE)."
    }

    Invoke-Az @('group', 'create', '--name', $ResourceGroupName, '--location', $Location, '--output', 'none') | Out-Null
    $existingDeploymentState = Invoke-Az @(
        'deployment', 'group', 'list',
        '--resource-group', $ResourceGroupName,
        '--query', "[?name=='banking-demo'].properties.provisioningState | [0]",
        '--output', 'tsv'
    )
    if (-not [string]::IsNullOrWhiteSpace([string]$existingDeploymentState)) {
        if ([string]$existingDeploymentState -ne 'Failed') {
            throw "A 'banking-demo' deployment already exists with state '$existingDeploymentState'. This script only allows retrying a failed deployment."
        }
        Write-Warning "The prior deployment failed but may have left billable resources in '$ResourceGroupName'. This retry generates new credentials and updates the deployment; inspect the resource group and new what-if before confirming."
    }

    Write-Output 'Reviewing the Azure deployment. No billable resources are created by this what-if.'
    Invoke-Az @(
        'deployment', 'group', 'what-if',
        '--resource-group', $ResourceGroupName,
        '--template-file', 'infra/main.bicep',
        '--parameters', "@$parameterFile",
        '--output', 'table'
    )

    $confirmation = Read-Host 'This demo creates billable Azure resources. Type CREATE to continue'
    if ($confirmation -cne 'CREATE') {
        Write-Output 'Cancelled. No billable resources were deployed.'
        exit 0
    }

    Invoke-Az @(
        'deployment', 'group', 'create',
        '--name', 'banking-demo',
        '--resource-group', $ResourceGroupName,
        '--template-file', 'infra/main.bicep',
        '--parameters', "@$parameterFile",
        '--output', 'json'
    )
}
finally {
    if (Test-Path -LiteralPath $parameterFile) {
        Remove-Item -LiteralPath $parameterFile -Force
    }
}
