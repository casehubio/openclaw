#!/usr/bin/env bash
# casehub_rest_client.sh — shared REST client for all casehub-* skills.
# Reads CASEHUB_BASE_URL and CASEHUB_API_KEY from environment.
#
# Usage:
#   casehub_rest_client.sh GET /path
#   casehub_rest_client.sh POST /path '{"key": "value"}'

set -euo pipefail

METHOD="${1:?METHOD required}"
PATH_SUFFIX="${2:?PATH required}"
BODY="${3:-}"

BASE_URL="${CASEHUB_BASE_URL:-http://localhost:8080}"
API_KEY="${CASEHUB_API_KEY:-}"

HEADERS=(-H "Content-Type: application/json" -H "Accept: application/json")
if [ -n "$API_KEY" ]; then
  HEADERS+=(-H "Authorization: Bearer $API_KEY")
fi

if [ "$METHOD" = "GET" ]; then
  curl -sf -X GET "${HEADERS[@]}" "${BASE_URL}${PATH_SUFFIX}"
else
  curl -sf -X "$METHOD" "${HEADERS[@]}" -d "$BODY" "${BASE_URL}${PATH_SUFFIX}"
fi
