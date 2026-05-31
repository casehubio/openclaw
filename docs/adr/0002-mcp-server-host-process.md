# ADR-0002: MCP Server Host Process

**Status:** Decided — Quarkus-embedded
**Date:** 2026-05-31
**Epic:** casehubio/openclaw#7
**Deciders:** Mark Proctor

---

## Context

Epic 7 requires an MCP server to expose CaseHub commitment tools and resources to OpenClaw
agents via MCPorter. Two viable implementation paths exist.

The MCP server wraps existing CaseHub REST APIs already served by the Quarkus app (`app/`
module). Its tools (`casehub_commit`, `casehub_done`, etc.) call the same casehub-engine,
casehub-work, and casehub-qhorus services already wired in the app module.

---

## Options Considered

### Option A — Standalone TypeScript process

A new `mcp/` npm package using `@modelcontextprotocol/sdk`. Runs as a separate HTTP/SSE
process on a second port (e.g. 8090). Calls the Quarkus app REST APIs over HTTP.

**Advantages:**
- The official Anthropic MCP SDK is TypeScript-first and the most mature implementation
- Consistent with the existing plugin (`plugin/`) — same language, same toolchain
- Independent deployability

**Disadvantages:**
- Two processes to deploy, manage, and monitor
- Two base URLs in OpenClaw config (`baseUrl: 8080`, `mcpUrl: 8090`)
- Network hop from MCP server → Quarkus for every tool call (commitment registration, done, etc.)
- The plugin cannot directly call the MCP server for commitment operations without
  introducing a triple-hop (plugin → MCP → Quarkus); the review identified this as
  a design defect forcing elimination of `mcp-client.ts` from the plugin anyway
- Separate npm package means separate release cadence from the Java components

### Option B — Quarkus-embedded MCP endpoint

Extend the existing Quarkus app (`app/`) with an MCP endpoint using the
`quarkus-mcp-server` extension (mcp4j). The MCP tools are backed directly by CDI beans
already in scope — no HTTP hop between MCP and service layer.

**Advantages:**
- Single process: MCP and REST on the same port (8080)
- Single base URL in OpenClaw config
- Tool implementations call CDI services directly — no network hop, no serialization
- Plugin calls Quarkus REST directly for commitment operations (same pattern as
  `channel-client.ts`) — no `mcp-client.ts`, no triple-hop
- One release artifact, one deployment unit
- `quarkus-mcp-server` (mcp4j) is actively maintained and supports SSE and
  streamable-HTTP transports — the two transports MCPorter requires

**Disadvantages:**
- Java implementation rather than TypeScript — diverges from the plugin's language
- `quarkus-mcp-server` is less mature than `@modelcontextprotocol/sdk`; edge cases
  may require workarounds
- MCP tool schema is defined in Java annotations rather than TypeScript types — less
  familiar to OpenClaw ecosystem contributors

---

## Decision

**Option B — Quarkus-embedded.**

The triple-hop defect identified in the review is not a fixable detail of Option A —
it is structural. If the plugin routes commitment operations through the MCP server,
that is wrong (MCP is an LLM-facing surface, not middleware). If the plugin bypasses
the MCP server and calls Quarkus REST directly, the MCP server adds no value as a
separate process: both the plugin and the MCP server are calling the same Quarkus
endpoints. The only remaining function of a separate TypeScript MCP process would be
to re-expose those endpoints in MCP protocol format — a pure protocol translation
layer that Quarkus can do natively.

Embedding MCP in Quarkus collapses this correctly: tool implementations are CDI method
calls, not HTTP calls. The plugin calls Quarkus REST directly. The MCP endpoint is
an additional protocol surface on the same server, not a middleware tier.

The Java/TypeScript language split is a real cost but a bounded one. The MCP tool
implementations are thin — each is one or two service calls. The annotation-based
schema definition in mcp4j is adequate for this surface area.

---

## Consequences

**Implementation changes from the original spec:**

- `mcp/` directory (standalone TypeScript package) is eliminated
- `plugin/src/mcp-client.ts` is eliminated — never created
- `plugin/src/commitment-manager.ts` calls Quarkus REST directly via `ChannelClient`
  pattern (or a shared `CasehubClient` extracted from `channel-client.ts`)
- `app/` gains a new resource class: `McpServerResource` (or equivalent mcp4j entry
  point) exposing `GET|POST /mcp` in streamable-HTTP transport
- OpenClaw config uses a single `baseUrl: http://localhost:8080` for both plugin and
  MCP server
- Build: `app/pom.xml` gains `quarkus-mcp-server` dependency

**Repository structure update:**

```
app/
└── src/main/java/.../
    ├── OpenClawDeliveryResource.java       ← existing
    ├── ChannelContextWindowResource.java   ← existing
    ├── EvictionScheduler.java              ← existing
    └── mcp/
        ├── CasehubMcpServer.java           ← NEW: mcp4j entry point
        ├── CommitmentTools.java            ← NEW: casehub_commit, casehub_done, etc.
        ├── WorkitemTools.java              ← NEW: casehub_create_workitem, casehub_queue
        ├── CaseTools.java                  ← NEW: casehub_open_case
        ├── QueryTools.java                 ← NEW: casehub_status
        └── resources/
            ├── CommitmentsResource.java    ← NEW: casehub://agent/{id}/commitments
            ├── CasesResource.java          ← NEW: casehub://agent/{id}/cases
            └── ChannelRecentResource.java  ← NEW: casehub://channel/{id}/recent
```

**Risk:** `quarkus-mcp-server` maturity. If a blocking issue is encountered during
implementation, the fallback is a minimal standalone TypeScript MCP server that calls
Quarkus REST — accepting the structural limitation of the separate process in exchange
for SDK maturity. This fallback requires an explicit decision to switch; it is not
the default path.

---

## References

- quarkus-mcp-server (mcp4j): https://docs.quarkiverse.io/quarkus-mcp-server/dev/
- MCP streamable-HTTP transport: https://spec.modelcontextprotocol.io/specification/
- Review finding: code review 2026-05-31, Critical Issue #4
- ADR-0001: openclaw hook implementation language (TypeScript chosen for plugin)
