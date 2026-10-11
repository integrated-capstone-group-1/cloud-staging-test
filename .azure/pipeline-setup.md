# GitHub Actions demo deployment

`.github/workflows/azure-deploy.yml` runs Maven verification for pull requests and deploys successful commits pushed to `main` to one GitHub Environment named `production`. It builds and pushes the 11 application images to Docker Hub, then SSHes to the existing Azure Oracle VM and starts the Docker Compose demo stack from `docker-compose.vm.yml`. The deployment uses the exact validated commit SHA as its image tag.

The VM stack reuses the existing VM and its persistent Oracle container, runs Redis, Kafka/Zookeeper, and the banking services on that VM, and connects to Azure-managed PostgreSQL. The Spring API gateway remains in the stack because the frontend uses it and it applies authentication and other gateway behavior. Observability containers are omitted for the demo.

## Before the first deployment

1. Provision the existing Azure demo resources and bootstrap Oracle/PostgreSQL as documented in the project setup. The Bicep template still contains AKS resources; this VM deployment workflow does not use AKS.
2. Configure the VM's inbound Network Security Group rule for public HTTP on port 80. The Bicep template declares this rule for new or updated deployments, but an already-deployed VM needs the rule applied separately. For the current deployment:

   ```powershell
   $resourceGroup = "rg-banking-demo-centralus"
   $vmName = az deployment group show --resource-group $resourceGroup --name banking-demo --query properties.outputs.oracleVmName.value --output tsv
   $nicId = az vm show --resource-group $resourceGroup --name $vmName --query "networkProfile.networkInterfaces[0].id" --output tsv
   $nsgId = az network nic show --ids $nicId --query networkSecurityGroup.id --output tsv
   $nsgName = $nsgId.Split("/")[-1]
   az network nsg rule create --resource-group $resourceGroup --nsg-name $nsgName --name banking-demo-http --priority 105 --direction Inbound --access Allow --protocol Tcp --source-address-prefixes Internet --source-port-ranges "*" --destination-address-prefixes "*" --destination-port-ranges 80
   ```

   Keep SSH limited to the IP range used when provisioning. Only the frontend on port 80 is published by Compose; the gateway, Oracle, Kafka, and service ports are not published to the Internet.
3. Confirm that the managed PostgreSQL endpoint is reachable from the VM. Redis runs as a `redis:7.4-alpine` container in the VM's Compose stack, uses a named volume for data, and is reachable only by the other Compose services. It does not need a separate Azure resource or inbound firewall rule.
4. Create a GitHub Environment named `production`.
5. Set these `production` environment variables:

   | Variable | Value |
   | --- | --- |
   | `DEMO_VM_HOST` | Public IP address or DNS name of the existing Oracle VM |
   | `DEMO_VM_USER` | VM SSH user, normally `azureuser` |
   | `POSTGRES_HOST` | Managed PostgreSQL server FQDN |
   | `POSTGRES_LEDGER_USERNAME` | Optional; defaults to `ledger_audit` |
   | `POSTGRES_CHANNEL_USERNAME` | Optional; defaults to `channel_user` |
   | `ORACLE_USERNAME` | Optional; defaults to `ledger_app` |
   | `DEMO_HTTP_PORT` | Optional; defaults to `80` |
   | `JAVA_TOOL_OPTIONS` | Optional JVM memory override; default limits each service JVM for a small demo VM |

6. Set these GitHub Actions secrets:

   | Secret | Value |
   | --- | --- |
   | `DOCKERHUB_USERNAME` | **Repository secret**; Docker Hub account used to publish and pull images |
   | `DOCKERHUB_TOKEN` | **Repository secret**; Docker Hub access token with push/pull permissions |
   | `DEMO_VM_SSH_PRIVATE_KEY` | OpenSSH private key matching the public key installed on the VM |
   | `POSTGRES_ADMIN_USER` | Managed PostgreSQL administrator login |
   | `POSTGRES_ADMIN_PASSWORD` | Managed PostgreSQL administrator password |
   | `POSTGRES_LEDGER_PASSWORD` | Password for the `ledger_audit` application role |
   | `POSTGRES_CHANNEL_PASSWORD` | Password for the `channel_user` application role |
   | `REDIS_PASSWORD` | Password used by the local Redis container and the banking services |
   | `ORACLE_PASSWORD` | Password of the existing Oracle `ledger_app` user |
   | `JWT_SECRET` | Application JWT signing secret (at least 256 bits) |

   The Docker Hub values must be repository secrets because the image-publishing job does not run inside the `production` environment. The VM and database secrets can be repository secrets or `production` environment secrets.

The workflow transfers a `.env` file to `/opt/banking-demo` with mode `0600`. It is not committed to Git. Docker Hub authentication is performed with a temporary Docker config that is removed after deployment.

## Seeding and deployment

- Pull requests to `main` run Maven `verify` only.
- Pushes to `main` run Maven `verify`, build/push all 11 images, deploy them to the VM, and wait for the frontend health endpoint.
- On the first deployment only, use **Actions → Banking services - validate and deploy → Run workflow** and enable `seed_postgres`. The seed script creates the two managed PostgreSQL databases and roles, applies the existing schema and seed SQL, and skips schema/data that are already initialized. Do not request reseeding for normal app deployments.
- After a successful deployment, open `http://<DEMO_VM_HOST>/`. This HTTP endpoint is for the demo only; do not use real banking credentials or data.

## Capacity and charges

The existing VM is a small 2-vCPU demo size. Heap limits help fit the services, but Oracle, Kafka, and all Java services together may still exceed its memory/CPU capacity or start slowly. A successful CI build only proves the images build; the deployment job's health check is the runtime gate. If the VM runs out of memory, the smallest practical next step is to free the AKS node's quota and resize the existing VM, not create another VM.

Do not delete the AKS cluster until the VM deployment and demo flows have been verified. AKS and managed PostgreSQL may continue to incur charges while they remain provisioned. The deployment workflow itself does not provision or delete Azure resources.
