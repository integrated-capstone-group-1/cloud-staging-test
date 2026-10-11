# GitHub Actions setup

The workflow `.github/workflows/azure-deploy.yml` validates pull requests and automatically deploys successful commits pushed to `main` to one GitHub Environment named `production`. It does not provision Azure resources. First run `scripts/provision-demo.ps1` and then `scripts/bootstrap-k8s.ps1`; these provision/configure the cluster dependencies, create the `banking` namespace and seed PostgreSQL. The workflow applies the Kubernetes workloads on the first successful deployment.

## One-time Azure setup

Central US (`centralus`) is selected because PostgreSQL Flexible Server provisioning is restricted for this subscription in East US 2. The subscription currently has 4 regional vCPUs available in Central US. The Bicep template uses all of them: one 2-vCPU `Standard_D2as_v7` AKS node plus one 2-vCPU Oracle VM. Azure reports PostgreSQL 17 and the `Standard_B1ms` tier as available in Central US. This is a resource-constrained demo configuration; it is not suitable as a production sizing baseline, and the Java services may start slowly or contend for CPU/memory. Redis runs as a single authenticated StatefulSet in AKS. Azure resource, storage, and network charges begin after the explicit `CREATE` confirmation.

The earlier failed deployment left resources in `rg-banking-demo-eastus2`, including a running AKS cluster. This setup uses a new resource group, `rg-banking-demo-centralus`, and does not remove or stop the East US 2 resources. Check and manage that previous resource group separately to avoid ongoing charges.

From the repository root, first validate the template and provision it:

```powershell
az bicep build --file infra\main.bicep --stdout | Out-Null
.\scripts\provision-demo.ps1 `
  -SshPublicKeyPath "$env:USERPROFILE\.ssh\id_ed25519.pub" `
  -SshAllowedCidr "YOUR_PUBLIC_IPV4/32"
```

The provisioning script creates the resource group, displays Azure's `what-if`, and requires typing `CREATE` before deploying any billable infrastructure. It generates database, Oracle, JWT, and Grafana passwords and places them in Key Vault; it does not use the development credentials from `docker-compose.yml`. Use the public key corresponding to your SSH private key and restrict SSH to your own public IPv4 CIDR.

After deployment completes, bootstrap the database schemas/seeds, transfer the Oracle init/seed SQL to the VM over SSH, and create the Kubernetes configuration:

```powershell
.\scripts\bootstrap-k8s.ps1 `
  -SshPrivateKeyPath "$env:USERPROFILE\.ssh\id_ed25519"
```

The bootstrap script uses the current Azure CLI identity, `kubectl`, `kubelogin`, and the matching SSH private key. It seeds the two managed PostgreSQL databases from `sql\postgres` and `sql\channel`, starts Oracle XE with the files under `sql\oracle`, and creates the Kubernetes secret/config maps, including a randomly generated Redis password. It does not print database or Redis credentials. Keep the namespace secrets private; Redis credentials are stored in the Kubernetes namespace secret.

## GitHub configuration

1. Create a GitHub Environment named `production`. Do not add required reviewers if deployment should proceed automatically as requested.
2. Add these environment variables to `production`:
   - `AZURE_CLIENT_ID`: client ID of the user-assigned managed identity used by GitHub Actions.
   - `AZURE_TENANT_ID`: Microsoft Entra tenant ID.
   - `AZURE_SUBSCRIPTION_ID`: Azure subscription ID.
   - `AZURE_RESOURCE_GROUP`: resource group containing AKS.
   - `AKS_CLUSTER_NAME`: AKS cluster name.
3. Add these repository Actions secrets for Docker Hub:
   - `DOCKERHUB_USERNAME`
   - `DOCKERHUB_TOKEN`: Docker Hub access token with permission to push the 11 service repositories and pull them from AKS.
4. After bootstrap completes, obtain the generated AKS name and create the GitHub OIDC identity/federated credential:

   ```powershell
   $aksName = az deployment group show `
     --resource-group rg-banking-demo-centralus `
     --name banking-demo `
     --query properties.outputs.aksName.value `
     --output tsv
   .\scripts\setup-azure-auth-for-pipeline.ps1 `
     -SubscriptionId b82a736e-f4ca-4443-a538-9aa8de016682 `
     -Location centralus `
     -IdentityResourceGroup rg-banking-pipeline-centralus `
     -IdentityName gh-banking-deploy `
     -AksResourceGroup rg-banking-demo-centralus `
     -AksClusterName $aksName `
     -GitHubRepository integrated-capstone-group-1/banking-backend-monorepo
   ```

   Copy the printed values into the `production` Environment variables. The script grants the identity AKS user-credential access and the `Azure Kubernetes Service RBAC Admin` role scoped to the `banking` namespace. The first-time bootstrap script separately installs Promtail's cluster-scoped pod-reader role using the administrator's local credentials.
5. Push to `main` after the bootstrap script completes. The workflow first updates the Docker Hub pull secret, applies the namespace workloads from `k8s/base`, updates all 11 application images to their validated commit-SHA tags, and waits for successful rollouts.

All application and Grafana Services are private `ClusterIP` Services. To access the frontend for a demo, run `kubectl port-forward --namespace banking service/frontend-web 8090:8090` and open `http://localhost:8090`. Use a TLS-enabled ingress/load balancer before exposing a banking application publicly.

## Azure OIDC identity

Run `scripts/setup-azure-auth-for-pipeline.ps1` from an Azure CLI session that has permission to create a user-assigned managed identity and role assignments. The script creates the identity in a separate resource group, creates the GitHub Environment federated credential, and assigns AKS access. It prints the values needed for the GitHub Environment variables.

The workflow uses OIDC rather than an Azure client secret. It uses the Docker Hub token only for image push/pull. Never commit cloud credentials or Kubernetes secrets.
