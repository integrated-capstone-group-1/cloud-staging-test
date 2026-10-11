#!/usr/bin/env bash
set -Eeuo pipefail

IFS= read -r dockerhub_username
IFS= read -r dockerhub_token
auth_dir="$(mktemp -d)"
trap 'rm -rf "$auth_dir"' EXIT
export DOCKER_CONFIG="$auth_dir"

printf '%s' "$dockerhub_token" \
  | docker login --username "$dockerhub_username" --password-stdin
unset dockerhub_token

bash /opt/banking-demo/scripts/prepare-demo-vm.sh
bash /opt/banking-demo/scripts/deploy-demo-vm.sh
docker logout
