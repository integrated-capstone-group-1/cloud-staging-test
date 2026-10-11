#!/usr/bin/env bash
set -Eeuo pipefail

cd /opt/banking-demo
compose=(docker compose --env-file .env -f docker-compose.vm.yml)

"${compose[@]}" config --quiet

if [[ "${SEED_POSTGRES:-false}" == "true" ]]; then
  "${compose[@]}" --profile seed run --rm postgres-seed
fi

"${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans

frontend_port="${DEMO_HTTP_PORT:-80}"
for attempt in $(seq 1 90); do
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:${frontend_port}/actuator/health" || true)"
  if [[ "$status" == "200" ]]; then
    echo "Demo frontend is responding successfully on port ${frontend_port}."
    exit 0
  fi
  sleep 10
done

echo "Demo frontend did not become healthy on port ${frontend_port}." >&2
"${compose[@]}" ps >&2
"${compose[@]}" logs --tail=100 frontend-web api-gateway >&2
exit 1
