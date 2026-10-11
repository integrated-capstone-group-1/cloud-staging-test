targetScope = 'resourceGroup'

@description('Azure region selected for the demo deployment.')
param location string = 'centralus'

@description('Short label used to create deterministic, unique resource names.')
@minLength(3)
@maxLength(20)
param environmentName string = 'bankdemo'

@description('Current stable AKS Kubernetes version returned by az aks get-versions.')
param kubernetesVersion string = '1.35'

@description('VM size eligible in Central US. The quota allows one 2-vCPU AKS node and one 2-vCPU Oracle VM.')
param vmSize string = 'Standard_D2as_v7'

@description('PostgreSQL administrator login.')
param postgresAdminUser string = 'bankingadmin'

@secure()
@description('Generated deployment-time PostgreSQL administrator password.')
param postgresAdminPassword string

@secure()
@description('Generated Oracle SYS/SYSTEM password stored in Key Vault.')
param oracleSysPassword string

@secure()
@description('Generated Oracle ledger_app password stored in Key Vault.')
param oracleAppPassword string

@secure()
@description('Generated ledger_audit database user password stored in Key Vault.')
param ledgerDatabasePassword string

@secure()
@description('Generated channel_user database password stored in Key Vault.')
param channelDatabasePassword string

@secure()
@description('Generated application JWT signing secret stored in Key Vault.')
param jwtSecret string

@secure()
@description('Generated Grafana administrator password stored in Key Vault.')
param grafanaPassword string

@description('SSH public key for administration of the Oracle VM. Private keys are never uploaded.')
param adminSshPublicKey string

@description('Linux account name used for SSH administration of the Oracle VM.')
param vmAdminUsername string = 'azureuser'

@description('Your public IPv4 CIDR allowed to SSH to the Oracle VM, normally x.x.x.x/32.')
param sshAllowedCidr string

@description('Object ID of the signed-in Azure deployment user, for Key Vault secret administration.')
param deployerObjectId string

var resourceToken = uniqueString(subscription().id, resourceGroup().id, location, environmentName)
var vnetName = 'azvn${resourceToken}'
var aksName = 'azaks${resourceToken}'
var postgresName = 'azpg${resourceToken}'
var keyVaultName = 'azkv${resourceToken}'
var vmName = 'azvm${resourceToken}'
var vmNsgName = 'aznsg${resourceToken}'
var vmNicName = 'aznic${resourceToken}'
var vmPublicIpName = 'azpip${resourceToken}'
var aksIdentityName = 'azid${resourceToken}'
var nodeSubnetPrefix = '10.20.0.0/22'
var oracleSubnetPrefix = '10.20.4.0/24'
var postgresAdminSecretName = 'azpgadm${resourceToken}'
var ledgerPasswordSecretName = 'azldg${resourceToken}'
var channelPasswordSecretName = 'azchn${resourceToken}'
var oracleSysSecretName = 'azorsys${resourceToken}'
var oracleAppSecretName = 'azorapp${resourceToken}'
var jwtSecretName = 'azjwt${resourceToken}'
var grafanaPasswordSecretName = 'azgraf${resourceToken}'
var keyVaultSecretsUserRoleId = '4633458b-17de-408a-b874-0445c86b69e6'
var keyVaultSecretsOfficerRoleId = 'b86a8fe4-44ce-4948-aee5-eccb2c155cd7'
var networkContributorRoleId = '4d97b98b-1d4f-4787-a291-c67834d212e7'
var oracleCloudInit = replace(
  replace(
    replace(
      replace(
        replace(loadTextContent('oracle-cloud-init.yml'), '__KEYVAULT_NAME__', keyVaultName),
        '__ORACLE_SYS_SECRET__',
        oracleSysSecretName
      ),
      '__ORACLE_APP_SECRET__',
      oracleAppSecretName
    ),
    '__ORACLE_APP_USER__',
    'ledger_app'
  ),
  '__ORACLE_IMAGE__',
  'gvenzl/oracle-xe:21-slim'
)

resource networkSecurityGroup 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: vmNsgName
  location: location
  properties: {
    securityRules: [
      {
        name: 'azssh${resourceToken}'
        properties: {
          priority: 100
          protocol: 'Tcp'
          access: 'Allow'
          direction: 'Inbound'
          sourceAddressPrefix: sshAllowedCidr
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '22'
        }
      }
      {
        name: 'azhttp${resourceToken}'
        properties: {
          priority: 105
          protocol: 'Tcp'
          access: 'Allow'
          direction: 'Inbound'
          sourceAddressPrefix: 'Internet'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '80'
        }
      }
      {
        name: 'azora${resourceToken}'
        properties: {
          priority: 110
          protocol: 'Tcp'
          access: 'Allow'
          direction: 'Inbound'
          sourceAddressPrefix: nodeSubnetPrefix
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '1521'
        }
      }
    ]
  }
}

resource virtualNetwork 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  properties: {
    addressSpace: {
      addressPrefixes: [
        '10.20.0.0/16'
      ]
    }
    subnets: [
      {
        name: 'aksnodes'
        properties: {
          addressPrefix: nodeSubnetPrefix
        }
      }
      {
        name: 'oracle'
        properties: {
          addressPrefix: oracleSubnetPrefix
          networkSecurityGroup: {
            id: networkSecurityGroup.id
          }
        }
      }
    ]
  }
}

resource aksIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: aksIdentityName
  location: location
}

resource aksNetworkRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(virtualNetwork.id, aksIdentity.id, networkContributorRoleId)
  scope: virtualNetwork
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', networkContributorRoleId)
    principalId: aksIdentity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
    publicNetworkAccess: 'Enabled'
    networkAcls: {
      bypass: 'AzureServices'
      defaultAction: 'Allow'
    }
  }
}

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: postgresName
  location: location
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '17'
    administratorLogin: postgresAdminUser
    administratorLoginPassword: postgresAdminPassword
    storage: {
      storageSizeGB: 32
      autoGrow: 'Disabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    authConfig: {
      passwordAuth: 'Enabled'
      activeDirectoryAuth: 'Disabled'
    }
    network: {
      publicNetworkAccess: 'Enabled'
    }
  }
}

resource postgresAzureServicesFirewall 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: postgres
  name: 'azfw${resourceToken}'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

resource aks 'Microsoft.ContainerService/managedClusters@2025-05-01' = {
  name: aksName
  location: location
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${aksIdentity.id}': {}
    }
  }
  sku: {
    name: 'Base'
    tier: 'Free'
  }
  properties: {
    dnsPrefix: 'az${resourceToken}'
    kubernetesVersion: kubernetesVersion
    nodeResourceGroup: 'aznrg${resourceToken}'
    enableRBAC: true
    aadProfile: {
      managed: true
      enableAzureRBAC: true
    }
    oidcIssuerProfile: {
      enabled: true
    }
    securityProfile: {
      workloadIdentity: {
        enabled: true
      }
    }
    addonProfiles: {
      azureKeyvaultSecretsProvider: {
        enabled: true
        config: {
          enableSecretRotation: 'true'
          rotationPollInterval: '2m'
        }
      }
    }
    agentPoolProfiles: [
      {
        name: 'sys'
        mode: 'System'
        count: 1
        vmSize: vmSize
        osType: 'Linux'
        type: 'VirtualMachineScaleSets'
        enableAutoScaling: false
        maxPods: 30
        vnetSubnetID: '${virtualNetwork.id}/subnets/aksnodes'
      }
    ]
    networkProfile: {
      networkPlugin: 'azure'
      networkPluginMode: 'overlay'
      networkDataplane: 'cilium'
      outboundType: 'loadBalancer'
      loadBalancerSku: 'standard'
      serviceCidr: '10.21.0.0/16'
      dnsServiceIP: '10.21.0.10'
      podCidr: '10.244.0.0/16'
    }
    apiServerAccessProfile: {
      enablePrivateCluster: false
    }
  }
  dependsOn: [
    aksNetworkRoleAssignment
  ]
}

resource vmPublicIp 'Microsoft.Network/publicIPAddresses@2024-05-01' = {
  name: vmPublicIpName
  location: location
  sku: {
    name: 'Standard'
  }
  properties: {
    publicIPAllocationMethod: 'Static'
    publicIPAddressVersion: 'IPv4'
  }
}

resource vmNic 'Microsoft.Network/networkInterfaces@2024-05-01' = {
  name: vmNicName
  location: location
  properties: {
    networkSecurityGroup: {
      id: networkSecurityGroup.id
    }
    ipConfigurations: [
      {
        name: 'primary'
        properties: {
          privateIPAllocationMethod: 'Dynamic'
          subnet: {
            id: '${virtualNetwork.id}/subnets/oracle'
          }
          publicIPAddress: {
            id: vmPublicIp.id
          }
        }
      }
    ]
  }
}

resource oracleVm 'Microsoft.Compute/virtualMachines@2024-07-01' = {
  name: vmName
  location: location
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    hardwareProfile: {
      vmSize: vmSize
    }
    storageProfile: {
      imageReference: {
        publisher: 'Canonical'
        offer: '0001-com-ubuntu-server-jammy'
        sku: '22_04-lts-gen2'
        version: 'latest'
      }
      osDisk: {
        name: 'azodsk${resourceToken}'
        createOption: 'FromImage'
        managedDisk: {
          storageAccountType: 'StandardSSD_LRS'
        }
      }
      dataDisks: [
        {
          lun: 0
          name: 'azddsk${resourceToken}'
          createOption: 'Empty'
          diskSizeGB: 64
          managedDisk: {
            storageAccountType: 'StandardSSD_LRS'
          }
        }
      ]
    }
    osProfile: {
      computerName: 'azoracle'
      adminUsername: vmAdminUsername
      customData: base64(oracleCloudInit)
      linuxConfiguration: {
        disablePasswordAuthentication: true
        ssh: {
          publicKeys: [
            {
              path: '/home/azureuser/.ssh/authorized_keys'
              keyData: adminSshPublicKey
            }
          ]
        }
      }
    }
    networkProfile: {
      networkInterfaces: [
        {
          id: vmNic.id
        }
      ]
    }
  }
  dependsOn: [
    keyVault
  ]
}

resource postgresAdminSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: postgresAdminSecretName
  properties: {
    value: postgresAdminPassword
    attributes: {
      enabled: true
    }
  }
}

resource ledgerPasswordSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: ledgerPasswordSecretName
  properties: {
    value: ledgerDatabasePassword
    attributes: {
      enabled: true
    }
  }
}

resource channelPasswordSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: channelPasswordSecretName
  properties: {
    value: channelDatabasePassword
    attributes: {
      enabled: true
    }
  }
}

resource oracleSysSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: oracleSysSecretName
  properties: {
    value: oracleSysPassword
    attributes: {
      enabled: true
    }
  }
}

resource oracleAppSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: oracleAppSecretName
  properties: {
    value: oracleAppPassword
    attributes: {
      enabled: true
    }
  }
}

resource jwtSecretResource 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: jwtSecretName
  properties: {
    value: jwtSecret
    attributes: {
      enabled: true
    }
  }
}

resource grafanaPasswordSecret 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: keyVault
  name: grafanaPasswordSecretName
  properties: {
    value: grafanaPassword
    attributes: {
      enabled: true
    }
  }
}

resource vmKeyVaultRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, oracleVm.id, keyVaultSecretsUserRoleId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUserRoleId)
    principalId: oracleVm.identity.principalId
    principalType: 'ServicePrincipal'
  }
}

resource aksKeyVaultRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, aks.id, keyVaultSecretsUserRoleId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsUserRoleId)
    principalId: aks.properties.addonProfiles.azureKeyvaultSecretsProvider.identity.objectId
    principalType: 'ServicePrincipal'
  }
}

resource deployerKeyVaultRoleAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, deployerObjectId, keyVaultSecretsOfficerRoleId)
  scope: keyVault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', keyVaultSecretsOfficerRoleId)
    principalId: deployerObjectId
    principalType: 'User'
  }
}

output aksName string = aks.name
output keyVaultName string = keyVault.name
output postgresServerName string = postgres.name
output postgresHostName string = postgres.properties.fullyQualifiedDomainName
output postgresAdminUser string = postgresAdminUser
output oracleVmName string = oracleVm.name
output vmNetworkSecurityGroupName string = networkSecurityGroup.name
output oracleVmPrivateIp string = vmNic.properties.ipConfigurations[0].properties.privateIPAddress
output oracleVmPublicIp string = vmPublicIp.properties.ipAddress
output postgresAdminSecretName string = postgresAdminSecret.name
output ledgerSecretName string = ledgerPasswordSecret.name
output channelSecretName string = channelPasswordSecret.name
output oracleSysSecretName string = oracleSysSecret.name
output oracleAppSecretName string = oracleAppSecret.name
output jwtSecretName string = jwtSecretResource.name
output grafanaVaultEntry string = grafanaPasswordSecret.name
