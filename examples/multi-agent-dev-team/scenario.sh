#!/usr/bin/env bash
# Start the multi-agent-dev-team example.
# Prerequisite: docker compose up -d

set -euo pipefail

echo "Starting multi-agent-dev-team demo..."
echo "This will run Planner → Coder → Reviewer, with a gate before merge."
echo ""

curl -s -X POST "http://localhost:8080/example/multi-agent-dev-team/start" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool

echo ""
echo "Demo complete. Run: docker compose logs casehub-openclaw to see the audit trail."
