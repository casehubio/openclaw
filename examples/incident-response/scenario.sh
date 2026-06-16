#!/usr/bin/env bash
# Start the incident-response example.
# Prerequisite: docker compose up -d
# Run this script twice in the same session to see ChannelContextWindow inject
# prior channel history into the Investigator's second turn.

set -euo pipefail

echo "Starting incident-response demo..."
echo "This will run Investigator → Resolver, with a gate before the config change."
echo ""
echo "When the gate fires, run: ./approve.sh"
echo "To reject and stop:       ./approve.sh reject"
echo ""

curl -s -X POST "http://localhost:8080/example/incident-response/start" \
  -H "Content-Type: application/json" \
  | python3 -m json.tool
