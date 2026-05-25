# channel_client.py — ChannelContextWindow REST client
#
# Calls GET /channel-context/{agentId}?since={sequenceNumber} on the
# casehub-openclaw app module's REST endpoint.
#
# Returns recent Qhorus channel messages formatted for LLM system prompt injection.
# Handles: network failures (fail open), overflow signals, TTL-empty signals.
#
# Implementation: pending Epic 5 (casehubio/openclaw#5)
