# context_hook.py — OpenClaw before_prompt_build hook
#
# Injects ChannelContextWindow content into the OpenClaw agent system prompt
# before each agent turn. Uses appendSystemContext (compaction-safe).
#
# Design constraints (from docs/specs/openclaw-integration.md §ChannelContextWindow):
# - Overflow: inject explicit notice, not silent empty
# - TTL expiry: inject "no activity in last N minutes", not silent empty
# - Cache unavailable: fail open — log warning, proceed without injection
# - since cursor: use sequenceNumber not wall-clock timestamp
#
# Implementation: pending Epic 5 (casehubio/openclaw#5)
