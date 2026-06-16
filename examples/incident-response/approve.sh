#!/usr/bin/env bash
# Approve (or reject) the current oversight gate.
# Usage: ./approve.sh          → approved (default)
#        ./approve.sh reject   → rejected

set -euo pipefail

# Gate ID is in the Quarkus log:
#   INFO  [OversightGateService] Gate opened: gateId=<uuid> agentId=... ...
GATE_ID=$(docker compose logs casehub-openclaw 2>/dev/null \
  | grep "Gate opened:" \
  | tail -1 \
  | grep -oE 'gateId=[^ ]+' \
  | cut -d= -f2)

if [ -z "$GATE_ID" ]; then
  echo "No open gate found. Has the scenario reached the oversight gate?"
  echo "Run: docker compose logs casehub-openclaw | grep 'Gate opened'"
  exit 1
fi

DECISION="${1:-approved}"
echo "Gate ID: $GATE_ID"
echo "Decision: $DECISION"

curl -s -X POST "http://localhost:8080/openclaw/delivery/oversight/${GATE_ID}" \
  -H "Content-Type: application/json" \
  -d "{\"output\": \"${DECISION}\"}" \
  | python3 -m json.tool
