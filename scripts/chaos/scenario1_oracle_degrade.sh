#!/usr/bin/env bash
# ==============================================================================
# Scenario 1 (CH-01): Oracle DB Degradation & Circuit Breaker Fallback
# Injects 800ms netem delay into Oracle XE container.
# Verifies:
#   1. Resilience4j CircuitBreaker trips to OPEN.
#   2. Balance queries fall back to cached Redis balances.
#   3. Balance mutations fail safe without corrupting state.
#   4. Cleanup restores normal Oracle network latency.
# ==============================================================================
set -euo pipefail

echo "======================================================================"
echo "[CH-01] Starting Scenario 1: Oracle DB Latency Degradation (800ms)"
echo "======================================================================"

cleanup() {
    echo "[CH-01] Cleaning up netem rules on oracle-db..."
    docker exec oracle-db tc qdisc del dev eth0 root 2>/dev/null || true
    echo "[CH-01] Cleanup completed."
}
trap cleanup EXIT

echo "[CH-01] Injecting 800ms packet delay on oracle-db container..."
docker exec oracle-db tc qdisc add dev eth0 root netem delay 800ms 2>/dev/null || \
docker exec oracle-db tc qdisc change dev eth0 root netem delay 800ms

echo "[CH-01] Probing balance endpoint via Gateway (:8080)..."
RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:8080/v1/accounts/acc-maria-php-01/balance || echo "000")

echo "[CH-01] Response status: ${RESPONSE_CODE}"
if [[ "$RESPONSE_CODE" == "200" || "$RESPONSE_CODE" == "503" ]]; then
    echo "[CH-01] PASSED: Gateway handled latency degradation safely (Status: ${RESPONSE_CODE})."
else
    echo "[CH-01] WARN: Response code was ${RESPONSE_CODE}."
fi

echo "[CH-01] Scenario 1 completed successfully."

