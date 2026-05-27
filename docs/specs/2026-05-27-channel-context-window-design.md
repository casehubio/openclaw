# ChannelContextWindow Service — Design Spec

**Date:** 2026-05-27
**Epic:** #3
**Status:** Design approved

---

## 1. Purpose

A short-term, agent-scoped, in-memory ring buffer of Qhorus channel activity. Bridges
OpenClaw's episodic turn model with Qhorus's continuous channel mesh by injecting recent
cross-agent channel context into the LLM system prompt before each agent turn.

ChannelContextWindow is the **intelligence layer**, not the correctness layer. Qhorus
(commitments, Watchdog, ledger) guarantees correctness regardless of whether the window
is working. A cache miss or service restart degrades agent intelligence for one turn —
it does not break system correctness.

---

## 2. Architecture

Three-tier decomposition matching the existing module structure:

```
core/    ChannelContextWindowService   — ring buffer, associate(), query(), evictExpired()
         ChannelRingBuffer             — per-channel bounded Deque, synchronized
         ContextMessage                — record: windowSeq, channelId, channelName,
                                         messageType, senderId, content, receivedAt
         WindowContent                 — query result record

casehub/ ChannelContextWindowObserver  — implements MessageObserver SPI, feeds core service

app/     ChannelContextWindowResource  — GET /channel-context/{agentId}?since={seq}
         EvictionScheduler             — @Scheduled TTL cleanup, calls service.evictExpired()
```

**Data flow:**

1. `MessageService.dispatch()` in Qhorus fanOut → `ChannelContextWindowObserver.onMessage(event)`
2. Observer → `ChannelContextWindowService.add(event)` → assigns `windowSeq`, routes to
   `ChannelRingBuffer` for that `channelId`
3. Python SDK calls `GET /channel-context/{agentId}?since={lastSeq}`
4. Service looks up `agentId → Set<UUID> channelIds`, aggregates messages from each buffer,
   merges sorted by `windowSeq`, returns `WindowContent`
5. Python SDK formats messages + injects overflow/TTL notices → `appendSystemContext`

---

## 3. agentId → Channels Mapping

### Why explicit registration

Three alternatives were considered and rejected:

- **Auto-discovery (senderId):** An agent receives cross-channel context, not just produces it.
  Grocery-agent must see finance-agent's observe channel posts even if it has never sent a
  message there. senderId-based discovery breaks the primary use case by construction.

- **Channel name convention:** Creates a hidden, unenforced contract between `CaseChannelProvider`
  (which names channels) and `ChannelContextWindow` (which would filter by name prefix). Any
  naming change silently breaks context delivery. Does not generalise to case-scoped shared
  channels where all agents share one observe channel.

- **Global window:** Returns all channel messages to all agents. Correct for a single-case POC;
  breaks at scale (cross-case information leakage, enormous system prompts) and forecloses auth
  retrofit — violating the `auth-retrofit-readiness` protocol.

### Design

`ChannelContextWindowService` exposes a public registration method:

```java
public void associate(String agentId, Set<UUID> channelIds)
```

Called by `WorkerProvisioner` (Epic 4) when an OpenClaw agent is provisioned for a case.
Registration is **additive** — channels are never removed from an agent's set. Calling
`associate()` twice for the same `agentId` merges the channel sets; it never replaces them.

Until Epic 4 wires the call, the service returns `WindowContent.noAssociation()` for all
queries. This is the correct fail-open state — the agent proceeds without injected context.
See issue #4 comment for the wiring requirement.

**On restart:** The in-memory registry is lost. Agents receive `noAssociation()` until
`WorkerProvisioner` provisions them for a new case step. Acceptable for a best-effort service.

---

## 4. Data Model

### `ContextMessage` (record, `core/`)

```java
record ContextMessage(
    long windowSeq,
    UUID channelId,
    String channelName,
    MessageType messageType,
    String senderId,
    String content,
    Instant receivedAt
) {}
```

`EVENT` messages are excluded at ingestion — `isAgentVisible()` returns false, and their
content is null per PP-20260508-90428f. No EVENT entries ever reach the ring buffer.

### `WindowContent` (record, `core/`)

```java
record WindowContent(
    List<ContextMessage> messages,
    int overflowCount,
    long lastWindowSeq,
    boolean agentHasAssociation,
    Instant lastChannelActivity
) {
    static WindowContent noAssociation() { ... }
}
```

**Python SDK usage of each field:**

| Field | Python SDK action |
|-------|------------------|
| `agentHasAssociation = false` | Skip injection silently |
| `overflowCount > 0` | Inject: "Note: N messages not retained (high volume). Full history in ledger." |
| `messages` empty + `lastChannelActivity` older than TTL | Inject: "No channel activity in the last N minutes." |
| `messages` non-empty | Format and inject as context |

---

## 5. `windowSeq` Cursor Design

The `since` parameter in the REST endpoint is a **global monotonic sequence** maintained by
`ChannelContextWindowService` via an `AtomicLong`. It is **not** the Qhorus per-channel
`sequenceNumber`.

Qhorus `sequenceNumber` is per-channel and restarts from 1 for each channel
(GE-20260501-b12416). Using it as a cross-channel cursor would be ambiguous — two messages
on different channels can both have `sequenceNumber = 1`. The ChannelContextWindow assigns
its own `windowSeq` to every ingested message, guaranteeing global monotonic ordering across
all channels.

The Python SDK tracks `lastWindowSeq` per agent session and passes it as `since` on subsequent
calls. On the first call or after restart, `since = 0` returns all buffered messages.

---

## 6. `ChannelRingBuffer` (package-private, `core/`)

Per-channel bounded `ArrayDeque<ContextMessage>`. All methods are `synchronized` on the
buffer instance. Lock contention is per-channel — concurrent writes to different channels
proceed independently.

**Overflow:** When `messages.size() >= maxSize`, the oldest entry is evicted (`pollFirst()`)
and `evictionCount` is incremented. Eviction count is cumulative since buffer creation.

**TTL — lazy (at query time):** `query()` filters entries where `receivedAt < now - ttl`.
This is the correctness mechanism — returned messages are always fresh.

**TTL — eager (scheduled sweep):** `evictExpired(Instant now)` removes entries older than
TTL from the deque. Called by `EvictionScheduler` every TTL/2. Without eager eviction, a
quiet channel could hold up to `maxSize` expired entries in memory indefinitely (they would
never overflow because no new messages arrive).

**`lastActivity`:** Tracks `receivedAt` of the most recent `add()` call. Populated by `add()`,
not by `evictExpired()`. Used by the REST endpoint to populate `WindowContent.lastChannelActivity`
for the Python SDK's idle notice.

---

## 7. `ChannelContextWindowService` (`@ApplicationScoped`, `core/`)

**Two registries:**

- `ConcurrentHashMap<UUID, ChannelRingBuffer> buffers` — one entry per channel; created
  eagerly on `associate()`, keyed by `channelId`
- `ConcurrentHashMap<String, Set<UUID>> agentChannels` — keyed by `agentId`; populated
  by `associate()`

**`add()` hot path:** calls `buffers.get(channelId)`. If `null` (channel not associated with
any agent), returns immediately — no lock, no buffer creation. Every dispatched Qhorus message
passes through this path, including messages on channels no agent watches. The cost is a single
hash lookup.

**`associate()` pre-creates buffers:** Calling `associate()` runs `buffers.computeIfAbsent()`
for each channel ID, ensuring buffers exist before any messages arrive. This keeps `add()` to
a non-blocking `get()`.

**`query()` logic:**
1. Look up `agentChannels.get(agentId)` → if absent: return `noAssociation()`
2. For each associated `channelId`: call `buffer.query(since, now)`
3. Merge all results, sort by `windowSeq`
4. Accumulate `evictionCount` and find the latest `lastActivity` across all channels
5. Compute `lastWindowSeq` = last message's `windowSeq` (or `since` if no messages)
6. Return `WindowContent`

**Configuration:**

```properties
casehub.openclaw.context-window.max-messages-per-channel=100
casehub.openclaw.context-window.ttl=PT30M
casehub.openclaw.context-window.ttl-cleanup-interval=PT15M
```

---

## 8. `ChannelContextWindowObserver` (`@ApplicationScoped`, `casehub/`)

Implements `MessageObserver` SPI. Scope: `LOCAL` (in-JVM, default).

```java
@Override
public void onMessage(MessageReceivedEvent event) {
    if (!event.messageType().isAgentVisible()) return;
    try {
        service.add(event);
    } catch (Exception e) {
        log.errorf(e, "ChannelContextWindow write failed for channel %s — ignoring",
                   event.channelName());
    }
}
```

`isAgentVisible()` encodes the EVENT exclusion rule and future-proofs against any new
non-visible type added to `MessageType`.

The `try/catch` wraps the entire call. Any unexpected failure in the service (NPE, assertion
error, OOM fragment) must not propagate back to Qhorus's fanOut path. Per the `MessageObserver`
SPI contract: implementations must not propagate exceptions.

Per the SPI javadoc: do not query Qhorus message state inside observer implementations — the
dispatcher fires before the enclosing transaction commits. This implementation does not query
Qhorus.

---

## 9. REST Endpoint (`app/`)

```
GET /channel-context/{agentId}?since={windowSeq}
```

Returns `200 OK` always. `404` would be semantically wrong — an unknown agentId is not an
error, it is the fail-open state (not yet wired by Epic 4). The Python SDK treats
`agentHasAssociation = false` as a silent skip.

`EvictionScheduler` (`@ApplicationScoped`, `app/`) holds the `@Scheduled` annotation and
calls `service.evictExpired()` on the configured interval. The scheduler lives in `app/`
rather than `core/` to keep `quarkus-scheduler` off the library module.

---

## 10. Failure Modes

| Failure | Behaviour | Contract |
|---------|-----------|---------|
| Ring buffer overflow | Oldest entry evicted; `evictionCount` incremented; overflow notice injected by Python SDK | Never silent empty |
| TTL expiry (all messages stale) | Filtered window empty; `lastChannelActivity` old; Python SDK injects idle notice | Never silent empty |
| `GET /channel-context` unavailable | Python SDK catches HTTP error, logs, proceeds without context | Fail open — agent turn continues |
| `add()` throws | Observer catches, logs, returns normally | Qhorus dispatch unaffected |
| Restart | In-memory state lost; `noAssociation()` until WorkerProvisioner re-associates | Fail open |
| Agent not yet associated (pre-Epic 4) | `noAssociation()` — Python SDK skips injection silently | Fail open |

---

## 11. Testing Strategy

| Test class | Module | Framework | What it tests |
|---|---|---|---|
| `ChannelRingBufferTest` | `core` | Pure JUnit 5 | Overflow, TTL, cursor, eviction, `lastActivity` |
| `ChannelContextWindowServiceTest` | `core` | `@QuarkusTest` | Routing, aggregation, windowSeq monotonicity, concurrency smoke |
| `ChannelContextWindowObserverTest` | `casehub` | Pure JUnit 5 + Mockito | EVENT filtering, exception isolation, scope |
| `ChannelContextWindowResourceTest` | `app` | `@QuarkusTest` + `@InjectMock` | HTTP routing, JSON serialisation, `since=0` default |

**Key invariants covered:**

- `EVENT` messages never reach the ring buffer
- Observer exceptions never propagate to Qhorus
- Unknown `agentId` → `200` with `agentHasAssociation=false` (not `404`)
- `windowSeq` is globally monotonic across all channels
- Overflow does not silently drop — `overflowCount` always reflects actual evictions
- `add()` for an unassociated channel is a silent, lock-free no-op
- `associate()` called twice for the same agent merges channels (does not replace)

---

## 12. Deferred / Out of Scope

| Item | Tracked |
|------|---------|
| WorkerProvisioner calling `associate()` | Issue #4 comment |
| Optional persistence backend for restart recovery | Future — SPI pattern if needed |
| Cross-channel watch subscriptions (agent explicitly subscribing to other agents' channels) | Future — `associate()` API already sufficient; Epic 4 decides which channels to include |
| Cluster-scope `MessageObserver` (multi-JVM) | Future — `LOCAL` is correct for single-JVM; CLUSTER requires Kafka or equivalent |
| Flyway migration for ChannelContextWindow state | **Superseded** — in-memory only by design (see issue #3 comment) |
