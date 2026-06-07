---
id: PP-20260607-84b26d
title: "MCP tool @ApplicationScoped beans must not cache entity associations in instance fields"
type: rule
scope: repo
applies_to: "app/mcp/ — any @Tool-annotated method or @ApplicationScoped bean in the openclaw app module that needs to resolve entity associations (e.g. correlationId → channelId)"
severity: important
refs:
  - docs/specs/2026-06-06-s-items-design.md
violation_hint: "A Map<String, UUID> or similar field populated at request time in an @ApplicationScoped bean, used to carry channelId, caseId, or other entity associations between tool invocations (e.g. commitmentId → channelId in CommitmentTools prior to #20)."
created: 2026-06-07
---

`@ApplicationScoped` beans are JVM-lifecycle singletons — their instance fields survive the duration of the process but are lost on Quarkus restart. MCP tool calls arrive at AI agent turn latency (hundreds of milliseconds to seconds); one extra DB read per call is invisible in this context. Using an instance field (e.g. `ConcurrentHashMap<String, UUID>`) to cache entity associations creates a crash recovery gap: after a restart the map is empty, tool calls fail with misleading errors (COMMITMENT_NOT_FOUND), and valid commitments become unreachable until the Watchdog expires them. The fix is always available: the underlying Qhorus entity (`Commitment.channelId`, `Channel.id`, etc.) persists to the database and is authoritative. Read from the entity on every tool call via `commitmentStore.findByCorrelationId()` or the equivalent store method rather than maintaining a parallel in-memory index.
