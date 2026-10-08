#!/usr/bin/env bash
# ==============================================================================
# Scenario 3 (CH-03): Zero-Trust Perimeter Port Scan (Bash Version)
# Verifies all internal ports refuse direct host connection.
# ==============================================================================
set -euo pipefail

echo "======================================================================"
echo "[CH-03] Starting Scenario 3: Perimeter Port Cloaking Scan (Bash)"
echo "======================================================================"

TARGET_HOST="127.0.0.1"
INTERNAL_PORTS=(1521 5432 6379 9092 2181 8081 8082 8083 8084 8085 8086 8087 8088 8089 3000 9090 3100)
PUBLIC_PORTS=(8080 8090)

LEAKED=0

echo "--- Scanning Protected Internal Ports ---"
for port in "${INTERNAL_PORTS[@]}"; do
    if nc -z -w 1 "$TARGET_HOST" "$port" 2>/dev/null || (echo > "/dev/tcp/$TARGET_HOST/$port") 2>/dev/null; then
        echo "[LEAK DETECTED] Port $port is reachable directly!"
        LEAKED=$((LEAKED + 1))
    else
        echo "[CLOAKED] Port $port refused connection as expected."
    fi
done

echo ""
echo "======================================================================"
if [ "$LEAKED" -eq 0 ]; then
    echo "[CH-03] PASSED: Zero-trust perimeter intact. 0 internal ports leaked!"
    exit 0
else
    echo "[CH-03] FAILED: $LEAKED internal ports are exposed on host!"
    exit 1
fi

