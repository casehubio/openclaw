---
layout: post
title: "Making OpenClaw Agents Accountable"
date: 2026-05-31
entry_type: note
subtype: diary
type: phase-update
projects: [casehub-openclaw]
tags: [quarkus, mcp, openclaw, accountability, skill-pack]
---

OpenClaw agents are capable. They call APIs, read calendars, send messages, run heartbeats. But when an agent commits to do something, there's no machine-readable record it ever happened. No deadline. No escalation if it goes silent. No audit trail of what it knew when it decided.

Epic 7 fixes that.

## The accountability layer OpenClaw was missing

The design went through three passes before we wrote a line of code. The first pass produced seven SKILL.md files — the obvious approach, matching OpenClaw's architecture. The second pass was a full critique session where I worked through why those skills would fail: the commitment lifecycle (`casehub-commit`, `casehub-done`) would depend on the LLM managing state across session resets, which LLMs do not do reliably. The third pass found the right architecture: move state management into infrastructure.

What we shipped is a four-layer system:

- **Quarkus MCP endpoint** — nine tools (`casehub_commit`, `casehub_done`, `casehub_reject`, `casehub_checkpoint`, `casehub_escalate`, `casehub_create_workitem`, `casehub_queue`, `casehub_status`) and two resources, served via MCPorter's streamable-HTTP transport. The MCP layer is LLM-facing only — it's what the agent calls when it explicitly manages a commitment.

- **TypeScript plugin hooks** — `before_tool_call`, `agent_end`, `session_start`. These handle the commitment lifecycle automatically, without LLM involvement. One commitment opens on the first tool call of an agent turn; it closes when the turn ends. Session restarts inject open commitments back into context. The LLM doesn't manage state; the plugin does.

- **Global skill** — `casehub-global`, installed with `always: true`. Injected into every agent's system prompt on every turn, explaining the available tools and when to use them explicitly. It does not instruct the agent to call `casehub_commit` — that would cause double-commitments when auto-commit is enabled.

- **Stateless SKILL.md files** — four skills for explicit user-initiated actions: workitem creation, case opening, queue routing, status queries. Each is a single MCP tool call with no state to manage.

## What changed mid-design

I assumed the MCP server would be a standalone TypeScript process. It was the natural choice — the official Anthropic SDK is TypeScript-first, and we already had a TypeScript plugin. But a code review caught the structural problem: the plugin hooks would need to call the MCP server for commitment operations, making it middleware instead of a protocol surface. That's the wrong use of MCP.

We switched to `quarkus-mcp-server-http` (mcp4j 1.11.1). The MCP tools are now CDI method calls — no network hop between MCP and the Quarkus services underneath. The plugin calls Quarkus REST directly, the same way it calls `/channel-context/{agentId}` for channel context injection. Single port, single base URL in OpenClaw config.

The mcp4j build-time validator caught something that would have bitten us in review: `@ToolArg` is not valid on `@ResourceTemplate` method parameters. Only `@ResourceTemplateArg` is. The `channelRecent` resource originally had an optional `since` parameter for incremental fetching. We removed it — resources return full current state from `since=0`; cursor management is the plugin's job, not the MCP resource consumer's.

## The platform coherence finding

While setting up TDD, Claude discovered that casehub-qhorus already ships 53 MCP tools in `QhorusMcpToolsBase` — including `send_message`, `list_my_commitments`, `get_commitment`, `register_watchdog`. The immediate question was whether we were duplicating something that already existed.

We're not. The Qhorus tools operate at the channel level: the agent needs a channelId, a correlationId, an inReplyTo message ID. The casehub-openclaw commitment tools operate at the obligation level: the agent provides its agentId and optionally a channelId; the tool looks up correlationId and inReplyTo automatically. The difference is who manages UUID state — and the answer is the infrastructure.

Both sets of tools call `MessageService.dispatch()`, the single Qhorus enforcement gate. We're not bypassing anything; we're providing a simpler interface to the same underlying operations.

## The review findings that mattered

A code reviewer caught three correctness issues before commit:

The `reject()` tool had no self-commit fallback. `done()` correctly handles both channel-backed and self-created commitments; `reject()` only handled the channel-backed case. An agent who creates a self-commit and then calls `casehub_reject` would get COMMITMENT_NOT_FOUND and leave the Watchdog armed indefinitely.

The `inReplyTo` handling was wrong. When the COMMAND message can't be found for a `done()` or `reject()` or `escalate()` call, the original code passed `null` for `inReplyTo`. The MessageDispatch builder throws `IllegalArgumentException` for null `inReplyTo` on DONE/DECLINE/HANDOFF — these speech acts require it. The correct behaviour is to return a tool error when the COMMAND can't be found, not attempt a dispatch that will throw.

Auto-commit on commitment tools creates recursive commitments. `before_tool_call` with `autoCommit: true` fired on every tool call, including `casehub_done`. The agent explicitly calling `casehub_done` to close a commitment would trigger auto-commit for that very call, opening a new commitment that would never be closed. The exclusion set needed to cover all CaseHub lifecycle tools, not just read-only ones.

All three were correctness defects, not style issues. Worth catching.

## What was genuinely surprising

The per-turn granularity for auto-commit was non-obvious. My first design was a stack of commitment IDs — one per tool call, closed in LIFO order at `agent_end`. The code review asked the right question: from the user's perspective, one instruction is one unit of work. If an agent calls five tools to fulfil one instruction, the ledger should show one commitment, not five. The Watchdog should fire once, not five times.

The stack collapses to a single optional ID per turn. The LIFO logic disappears. The design is simpler and more correct.

## The test numbers

86 Java tests, 34 TypeScript tests. The Java tests cover each tool's happy path and error paths as unit tests (constructor injection, plain Mockito, fast), plus `@QuarkusTest` coverage for the plugin REST endpoints. The TypeScript tests cover the per-turn commitment flag, auto-commit exclusions, escalation map-clearing, session_start injection, and fail-open behaviour on Quarkus unavailability.

The pre-existing `ChannelInitialisedEvent` constructor change — a new `recovered: boolean` parameter added by a qhorus dependency update — broke two existing tests silently. We fixed those on the way through.

## What this makes possible

The roadmap from here involves multi-agent coordination (broadcast, vote, handoff), self-governance (policy checks, second-agent review), persistent intelligence (CaseMemoryStore recall across sessions), and eventually economic participation — agents managing budgets, generating auditable financial records.

Each phase publishes new skills to ClawHub. Agents that installed the base pack get each new capability as it ships.

The base pack establishes the claim: OpenClaw agents can be accountable, not just capable. The Git analogy I keep coming back to — Git didn't change what developers wrote, it changed the coordination and accountability model around writing code. This does the same for agent work.
