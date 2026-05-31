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

## 2026-05-31 — mcp4j @ResourceTemplate constraint: no @ToolArg on resource params

`@ToolArg` is not allowed on `@ResourceTemplate` method parameters — only `@ResourceTemplateArg`
is valid. The `channelRecent` resource originally accepted an optional `since` parameter via
`@ToolArg`. Removed; resources always return from `since=0`. Incremental fetching is the plugin's
concern via `before_prompt_build` cursor state.

## 2026-05-31 — casehub_reject missing self-commit fallback path (code review)

Initial `reject()` had no self-commit path (unlike `done()`). Added `selfCommit_reject()` calling
`CommitmentService.decline(correlationId)`. All terminal dispatches (DONE/DECLINE/HANDOFF) now
return `COMMAND_NOT_FOUND` when `findCommandMessageId()` returns -1, rather than attempting dispatch
with null `inReplyTo`. Platform contract: all three require `inReplyTo`.

## 2026-05-31 — AUTO_COMMIT_EXCLUDED_TOOLS: CaseHub lifecycle tools exempt from auto-commit

`before_tool_call` auto-commit would fire when the LLM explicitly calls `casehub_done`,
`casehub_reject`, etc., creating recursive nested commitments. `AUTO_COMMIT_EXCLUDED_TOOLS` covers
all CaseHub commitment tools in addition to read-only tools.

## 2026-05-31 — PluginCommitResource: plugin REST API separate from MCP endpoint

The plugin's `before_tool_call` / `agent_end` hooks call `/openclaw/plugin/commit` and
`/openclaw/plugin/done` (simple REST) — not the MCP endpoint. MCP is LLM-facing only (MCPorter).
Plugin hooks run outside LLM turns and need a direct REST interface. `PluginCommitResource` is
`@ApplicationScoped`, delegates to `CommitmentService` and `CommitmentStore`.
