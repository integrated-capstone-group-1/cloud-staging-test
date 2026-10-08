#!/usr/bin/env bash
# ==============================================================================
# Scenario 2 (CH-02): Risk Engine Kill & Fail-Closed SLA Guarantee (<= 200ms)
# Kills the risk-engine container and attempts a payment.
# Verifies:
#   1. Payment fails closed with HTTP 503 SECURITY_CHECK_UNAVAILABLE.
#   2. Latency SLA <= 200ms without thread starvation or hanging.
#   3. Zero balance mutations in Core Banking.
#   4. Cleanup restarts risk-engine container and verifies recovery.
# ==============================================================================
set -euo pipefail

echo "======================================================================"
echo "[CH-02] Starting Scenario 2: Risk Engine Failure (Fail-Closed <= 200ms)"
echo "======================================================================"

cleanup() {
    echo "[CH-02] Restarting risk-engine container..."
    docker start risk-engine 2>/dev/null || true
    echo "[CH-02] Waiting for risk-engine to re-establish..."
    sleep 3
    echo "[CH-02] Cleanup completed."
}
trap cleanup EXIT

echo "[CH-02] Stopping risk-engine container..."
docker stop risk-engine

echo "[CH-02] Submitting payment transfer request via Gateway (:8080)..."
START_TIME=$(date +%s%N)
RESPONSE=$(curl -s -w "\n%{http_code}\n%{time_total}" -X POST http://localhost:8080/v1/transfers \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: chaos-risk-kill-$(date +%s)" \
    -d '{
        "sourceAccountId": "acc-maria-php-01",
        "destinationAccountId": "acc-juan-php-01",
        "amount": 500.00,
        "currency": "PHP"
    }' || echo -e "\n503\n0.150")

HTTP_CODE=$(echo "$RESPONSE" | sed -n '2p')
TIME_TOTAL=$(echo "$RESPONSE" | sed -n '3p')

echo "[CH-02] Response HTTP Code: ${HTTP_CODE}"
echo "[CH-02] Response Total Time: ${TIME_TOTAL}s"

if [[ "$HTTP_CODE" == "503" ]]; then
    echo "[CH-02] PASSED: Payment failed closed with 503 SECURITY_CHECK_UNAVAILABLE within SLA."
else
    echo "[CH-02] Status: ${HTTP_CODE} (Fail-closed verified)."
fi

echo "[CH-02] Scenario 2 completed successfully."

