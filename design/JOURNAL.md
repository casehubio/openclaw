# Design Journal — issue-7-openclaw-skill-pack

## 2026-05-31 — Platform coherence: Qhorus already has 53 MCP tools

During TDD setup, discovered `QhorusMcpToolsBase` / `QhorusMcpTools` in casehub-qhorus with
53 tools including `send_message`, `list_my_commitments`, `get_commitment`, `register_watchdog`.

**Decision:** CommitmentTools in casehub-openclaw are NOT duplicating Qhorus tools. They provide
a higher-level abstraction: agent only needs `casehub_commit(agentId, task, channelId)` — the
tool looks up correlationId, inReplyTo, and obligor automatically. LLM never tracks UUIDs or
message IDs.

Both CommitmentTools and QhorusMcpTools call `MessageService.dispatch()` — same enforcement gate.
casehub-openclaw wraps it with domain knowledge (agent-scoped commitment lookup, CaseHub
WorkItem/Case creation), not duplicated infrastructure.

`casehub://agent/{id}/commitments` as an MCP resource (URI) is a different access pattern from
Qhorus's `list_my_commitments` tool. Both use CommitmentStore internally.
