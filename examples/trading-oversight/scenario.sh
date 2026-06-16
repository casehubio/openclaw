#!/usr/bin/env bash
# Start the trading-oversight example.
# Prerequisite: docker compose up -d

set -euo pipefail

echo "Starting trading-oversight demo..."
echo "This will run Signal → Risk → Execution, with a gate before the order is placed."
echo ""
echo "When the gate fires, run: ./approve.sh"
echo "To reject the trade:      ./approve.sh reject"
echo ""

curl -s -X POST "http://localhost:8080/example/trading-oversight/start" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool
