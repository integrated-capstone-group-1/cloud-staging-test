#!/usr/bin/env bash
# ==============================================================================
# Master Chaos Suite Runner (Task T-014 / Done-When Command)
# Executes all 4 Graded Chaos Scenarios (CH-01 through CH-04) per ADR-003.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "${LOG_DIR}"

echo "======================================================================"
echo "    CAPSTONE RETAIL BANKING - GRADED CHAOS TEST SUITE (T-014)"
echo "======================================================================"
echo "Execution Timestamp: $(date)"
echo ""

TOTAL_SCENARIOS=4
PASSED_SCENARIOS=0

run_scenario() {
    local name="$1"
    local script="$2"
    echo ">>> Running ${name}..."
    if bash "${SCRIPT_DIR}/${script}" > "${LOG_DIR}/${script}.log" 2>&1; then
        echo ">>> [SUCCESS] ${name} PASSED"
        PASSED_SCENARIOS=$((PASSED_SCENARIOS + 1))
    else
        echo ">>> [FAILURE] ${name} FAILED (See ${LOG_DIR}/${script}.log)"
    fi
    echo "----------------------------------------------------------------------"
}

# Execute Graded Scenarios CH-01 to CH-04
run_scenario "CH-01: Oracle DB Latency Degradation & Circuit Breaker" "scenario1_oracle_degrade.sh"
run_scenario "CH-02: Risk Engine Failure & Fail-Closed SLA"           "scenario2_risk_kill.sh"
run_scenario "CH-03: Zero-Trust Perimeter Port Cloaking Scan"         "scenario3_perimeter_scan.sh"
run_scenario "CH-04: PostgreSQL Outage & Outbox Decoupled Parity"      "scenario4_postgres_outage.sh"

echo ""
echo "======================================================================"
echo "                         CHAOS TEST RESULTS"
echo "======================================================================"
echo "Total Scenarios Executed: ${TOTAL_SCENARIOS}"
echo "Passed: ${PASSED_SCENARIOS}"
echo "Failed: $((TOTAL_SCENARIOS - PASSED_SCENARIOS))"

if [ "${PASSED_SCENARIOS}" -eq "${TOTAL_SCENARIOS}" ]; then
    echo ""
    echo ">>> ALL GRADED CHAOS SCENARIOS PASSED WITH ZERO DATA LOSS! <<<"
    exit 0
else
    echo ""
    echo ">>> SOME CHAOS SCENARIOS FAILED. CHECK LOGS IN scripts/chaos/logs/ <<<"
    exit 1
fi

