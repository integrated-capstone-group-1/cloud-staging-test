#!/usr/bin/env bash
set -Eeuo pipefail

if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  apt-get install -y docker.io
fi

systemctl enable --now docker

if ! docker compose version >/dev/null 2>&1; then
  apt-get update
  if ! apt-get install -y docker-compose-v2; then
    apt-get install -y docker-compose-plugin
  fi
fi

docker compose version

# Disable strict reverse-path filtering so Docker containers can reply to
# requests arriving on the public IP (Azure Standard SKU public IP + Docker
# userspace proxy otherwise drops outbound reply packets asymmetrically).
cat > /etc/sysctl.d/99-docker-rpfilter.conf <<'EOF'
net.ipv4.conf.all.rp_filter=0
net.ipv4.conf.eth0.rp_filter=0
EOF
sysctl -p /etc/sysctl.d/99-docker-rpfilter.conf
