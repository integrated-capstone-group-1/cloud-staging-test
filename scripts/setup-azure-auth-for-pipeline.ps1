param(
    [Parameter(Mandatory = $true)]
    [string]$SubscriptionId,

    [Parameter(Mandatory = $true)]
    [string]$Location,

    [Parameter(Mandatory = $true)]
    [string]$IdentityResourceGroup,

    [Parameter(Mandatory = $true)]
    [string]$IdentityName,

    [Parameter(Mandatory = $true)]
    [string]$AksResourceGroup,

    [Parameter(Mandatory = $true)]
    [string]$AksClusterName,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[^/]+/[^/]+$')]
    [string]$GitHubRepository
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

Invoke-Az @('account', 'set', '--subscription', $SubscriptionId) | Out-Null
Invoke-Az @('group', 'create', '--name', $IdentityResourceGroup, '--location', $Location, '--output', 'none') | Out-Null

$identity = (Invoke-Az @(
    'identity', 'create',
    '--resource-group', $IdentityResourceGroup,
    '--name', $IdentityName,
    '--location', $Location,
    '--output', 'json'
) | ConvertFrom-Json)

$subject = "repo:$GitHubRepository`:environment:production"
Invoke-Az @(
    'identity', 'federated-credential', 'create',
    '--resource-group', $IdentityResourceGroup,
    '--identity-name', $IdentityName,
    '--name', 'github-production',
    '--issuer', 'https://token.actions.githubusercontent.com',
    '--subject', $subject,
    '--audiences', 'api://AzureADTokenExchange',
    '--output', 'none'
) | Out-Null

$aksResourceId = Invoke-Az @(
    'aks', 'show',
    '--resource-group', $AksResourceGroup,
    '--name', $AksClusterName,
    '--query', 'id',
    '--output', 'tsv'
)

Invoke-Az @(
    'role', 'assignment', 'create',
    '--assignee-object-id', $identity.principalId,
    '--assignee-principal-type', 'ServicePrincipal',
    '--role', 'Azure Kubernetes Service Cluster User Role',
    '--scope', $aksResourceId,
    '--output', 'none'
) | Out-Null

Invoke-Az @(
    'role', 'assignment', 'create',
    '--assignee-object-id', $identity.principalId,
    '--assignee-principal-type', 'ServicePrincipal',
    '--role', 'Azure Kubernetes Service RBAC Admin',
    '--scope', "$aksResourceId/namespaces/banking",
    '--output', 'none'
) | Out-Null

$tenantId = Invoke-Az @('account', 'show', '--query', 'tenantId', '--output', 'tsv')

Write-Output 'Set these GitHub production Environment variables:'
Write-Output "AZURE_CLIENT_ID=$($identity.clientId)"
Write-Output "AZURE_TENANT_ID=$tenantId"
Write-Output "AZURE_SUBSCRIPTION_ID=$SubscriptionId"
Write-Output "AZURE_RESOURCE_GROUP=$AksResourceGroup"
Write-Output "AKS_CLUSTER_NAME=$AksClusterName"
Write-Output "Federated subject: $subject"
