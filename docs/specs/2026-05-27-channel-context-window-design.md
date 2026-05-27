# ChannelContextWindow Service — Design Spec

**Date:** 2026-05-27
**Epic:** #3
**Status:** Design approved — v2 (post-review)

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
                                         messageType, senderId, correlationId,
                                         content, receivedAt
         WindowContent                 — query result record

casehub/ ChannelContextWindowObserver  — implements MessageObserver SPI, feeds core service

app/     ChannelContextWindowResource  — GET /channel-context/{agentId}?since={seq}
         EvictionScheduler             — @Scheduled TTL cleanup, calls service.evictExpired()
```

**Data flow:**

1. `MessageService.dispatch()` in Qhorus fanOut → `ChannelContextWindowObserver.onMessage(event)`
2. Observer → `ChannelContextWindowService.add(event)` → assigns `windowSeq`, stamps
   `receivedAt = Instant.now()`, routes to `ChannelRingBuffer` for that `channelId`
3. Python SDK calls `GET /channel-context/{agentId}?since={lastWindowSeq}`
4. Service looks up `agentId → Set<UUID> channelIds`, aggregates messages from each buffer,
   merges sorted by `windowSeq`, returns `WindowContent`
5. Python SDK injects messages and any relevant notices → `appendSystemContext`

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

**Timing gap — channel creation vs. provisioning:** `CaseChannelProvider` creates channels
before `WorkerProvisioner` provisions the agent. Messages dispatched to those channels in the
gap are silently dropped by `add()` (no buffer exists yet). This is acceptable: the window is
intelligence, not correctness. Epic 4's design must decide whether `CaseChannelProvider` should
call `associate()` eagerly (registering owned channels at creation time) and `WorkerProvisioner`
should call it again for observe channels it doesn't own. See §12.

**Channel accumulation:** `associate()` is additive and never removes channels. If `agentId`
is a logical identifier reused across cases (e.g., "finance-agent" always maps to the same ID),
the watched channel set grows with each case. Buffers for resolved cases remain in memory until
restart. For deployments that reuse agent IDs across many cases, this produces intelligence
bloat and unbounded memory growth within a process lifetime. See §12 for deferred `disassociate()`.

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
    String correlationId,   // nullable; present for RESPONSE, DONE, FAILURE, DECLINE, HANDOFF
    String content,
    Instant receivedAt
) {}
```

**`EVENT` messages are excluded at ingestion** — `isAgentVisible()` returns false, and their
content is null per PP-20260508-90428f. No EVENT entries ever reach the ring buffer.

**`correlationId`** is included because it links RESPONSE to QUERY and DONE to COMMAND —
threading information the LLM needs to understand "this DONE refers to the COMMAND I was given
earlier." It is nullable: COMMAND and QUERY originate correlations; other types carry the
correlation they were issued with; EVENT has none.

**`receivedAt`** is stamped by `ChannelContextWindowService.add()` using `Instant.now()` at
ingestion time — not Qhorus's persistence timestamp. `MessageReceivedEvent` carries no timestamp.
The observer is called synchronously in the Qhorus fanOut path, so the delay between Qhorus
persisting the message and `add()` executing is minimal. `receivedAt` is used only for TTL
eviction, where this precision is sufficient. It is not used for ordering — `windowSeq` governs
ordering.

### `WindowContent` (record, `core/`)

```java
record WindowContent(
    List<ContextMessage> messages,
    long lastEvictionWindowSeq,  // -1 if no eviction has occurred; otherwise: windowSeq of
                                 // most recently evicted message across all associated channels
    long lastWindowSeq,          // max windowSeq of returned messages; 'since' if none returned
    long currentWindowSeq,       // service's current AtomicLong value at query time
    boolean agentHasAssociation,
    Instant lastChannelActivity  // Instant.EPOCH if no messages ever received on any
                                 // associated channel; never null
) {
    static WindowContent noAssociation() { ... }
}
```

**Python SDK usage — all notices are additive, not mutually exclusive:**

| Condition | Python SDK action |
|-----------|------------------|
| `agentHasAssociation = false` | Skip injection silently (not yet wired) |
| `since > currentWindowSeq` | Service restarted — reset cursor to 0 and skip this turn |
| `lastEvictionWindowSeq > since` | Prepend: "Note: Some messages evicted (high volume). Full history in ledger." |
| `messages` non-empty | Format and append messages as context |
| `messages` empty AND `lastChannelActivity` older than TTL | Append: "No channel activity in the last N minutes." |

Overflow notice and messages are **additive**: when eviction occurred but newer messages still
exist in the buffer, the SDK injects both the overflow notice (partial-view warning) and the
available messages. An if/elif structure that suppresses messages when overflow is present is
incorrect.

The cursor advances unconditionally to `lastWindowSeq` after each successful call (excluding
the restart-reset case).

---

## 5. `windowSeq` Cursor Design

The `since` parameter in the REST endpoint is a **global monotonic sequence** maintained by
`ChannelContextWindowService` via an `AtomicLong`. It is **not** the Qhorus per-channel
`sequenceNumber`.

Qhorus `sequenceNumber` is per-channel and restarts from 1 for each channel
(GE-20260501-b12416). `MessageReceivedEvent` carries no `sequenceNumber` field. Using Qhorus
sequencing as a cross-channel cursor is both infeasible (field absent) and semantically wrong
(ambiguous across channels). The ChannelContextWindow assigns its own `windowSeq` to every
ingested message, guaranteeing global monotonic ordering across all channels.

**Restart reset detection:**

After a service restart, the `AtomicLong` resets to 0. New messages get `windowSeq` 1, 2, 3…
A Python SDK holding `lastWindowSeq = 50` would send `since=50`. All new messages have seq < 50
and would never be returned — permanent silence.

`WindowContent.currentWindowSeq` carries the service's current `AtomicLong` value at query time.
The Python SDK detects a reset when `since > currentWindowSeq`:
- After restart with no messages ingested: `currentWindowSeq = 0`; any `since > 0` triggers reset.
- After restart with messages 1–3 ingested: `currentWindowSeq = 3`; `since=50 > 3` triggers reset.
- Normal operation: `currentWindowSeq` is always ≥ any cursor the SDK holds; no false reset.
- Fresh start: `since = 0`; `0 > currentWindowSeq` is always false; no reset.

On reset: the SDK discards its cursor and skips injection for that turn. Next turn it calls
with `since=0` and receives all buffered messages.

---

## 6. `ChannelRingBuffer` (package-private, `core/`)

Per-channel bounded `ArrayDeque<ContextMessage>`. All methods are `synchronized` on the
buffer instance. Lock contention is per-channel — concurrent writes to different channels
proceed independently.

**Initial state:** `lastActivity = Instant.EPOCH`; `lastEvictionWindowSeq = -1` (no eviction).

**Overflow:** When `messages.size() >= maxSize`, the oldest entry is evicted (`pollFirst()`)
and `lastEvictionWindowSeq` is updated to that message's `windowSeq`. This replaces a
cumulative `evictionCount`: the Python SDK only needs to know whether an eviction is *relevant*
to the current cursor — i.e., whether `lastEvictionWindowSeq > since`. A cumulative count would
cause the overflow notice to fire forever after any historical eviction, regardless of whether
it has any bearing on what the SDK has seen.

**TTL — lazy (at query time):** `query()` filters entries where `receivedAt < now - ttl`.
Returned messages are always fresh.

**TTL — eager (scheduled sweep):** `evictExpired(Instant now)` removes entries older than TTL
from the deque. Called by `EvictionScheduler` at the TTL interval. Without eager eviction, a
quiet channel could hold up to `maxSize` expired entries in memory indefinitely.

**`lastActivity`:** Tracks `receivedAt` of the most recent `add()` call. Initialised to
`Instant.EPOCH`. Updated only by `add()`, not by `evictExpired()`. Used to populate
`WindowContent.lastChannelActivity` for the Python SDK's idle notice. `Instant.EPOCH` means
no messages have been received since the channel was associated — Python SDK treats this as
"idle since association" and injects the idle notice on an empty window.

---

## 7. `ChannelContextWindowService` (`@ApplicationScoped`, `core/`)

**Two registries:**

- `ConcurrentHashMap<UUID, ChannelRingBuffer> buffers` — one entry per channel; created
  eagerly on `associate()`, keyed by `channelId`
- `ConcurrentHashMap<String, Set<UUID>> agentChannels` — keyed by `agentId`; populated
  by `associate()`

**`add()` hot path:** calls `buffers.get(channelId)`. If `null` (channel not associated with
any agent), returns immediately — no lock, no buffer creation. Every dispatched Qhorus message
passes through this path. The cost is a single hash lookup.

**`associate()` pre-creates buffers:** `buffers.computeIfAbsent()` for each channel ID ensures
buffers exist before any messages arrive. Keeps `add()` to a non-blocking `get()`.

**`query()` logic:**
1. Look up `agentChannels.get(agentId)` → if absent: return `noAssociation()`
2. For each associated `channelId`: call `buffer.query(since, now)`
3. Merge all results, sort by `windowSeq`
4. Track per-channel: `lastEvictionWindowSeq` (max across channels), `lastActivity`
   (max across channels)
5. `lastWindowSeq` = last returned message's `windowSeq`; if no messages: `since`
6. `currentWindowSeq` = `this.windowSeq.get()`
7. Return `WindowContent`

**Configuration:**

```properties
casehub.openclaw.context-window.max-messages-per-channel=100
casehub.openclaw.context-window.ttl=PT30M
```

`ttl-cleanup-interval` is intentionally absent as a separate property — the scheduler uses
`ttl` directly. An independent `ttl-cleanup-interval` config creates a misconfiguration risk
where the interval exceeds the TTL, holding stale entries in memory well beyond their expiry.

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

The `try/catch` wraps the entire call. Any unexpected failure in the service must not propagate
back to Qhorus's fanOut path. Per the `MessageObserver` SPI contract: implementations must not
propagate exceptions.

Per the SPI javadoc: do not query Qhorus message state inside observer implementations — the
dispatcher fires before the enclosing transaction commits. This implementation does not query
Qhorus.

`receivedAt` in `ContextMessage` is stamped inside `service.add()` using `Instant.now()` at
ingestion time. The observer is called synchronously in the Qhorus dispatch path, so `receivedAt`
closely approximates Qhorus's persistence timestamp — close enough for TTL eviction purposes.

---

## 9. REST Endpoint (`app/`)

```
GET /channel-context/{agentId}?since={windowSeq}
```

`since` defaults to `0` when omitted — returns all buffered messages for the agent.

Returns `200 OK` always. `404` would be semantically wrong — an unknown agentId is not an
error, it is the fail-open state (not yet wired by Epic 4). The Python SDK treats
`agentHasAssociation = false` as a silent skip.

`EvictionScheduler` (`@ApplicationScoped`, `app/`) holds the `@Scheduled` annotation and
calls `service.evictExpired()` at the `ttl` interval. The scheduler lives in `app/` rather
than `core/` to keep `quarkus-scheduler` off the library module.

```java
@Scheduled(every = "${casehub.openclaw.context-window.ttl:PT30M}")
void evict() {
    service.evictExpired();
}
```

---

## 10. Failure Modes

| Failure | Behaviour | Contract |
|---------|-----------|---------|
| Ring buffer overflow | Oldest evicted; `lastEvictionWindowSeq` updated; SDK injects notice only if eviction is after `since` | Never silent empty |
| TTL expiry (all messages stale) | Filtered window empty; `lastChannelActivity` = `Instant.EPOCH` or old; SDK injects idle notice | Never silent empty |
| Service restart | `currentWindowSeq` resets; SDK detects `since > currentWindowSeq`; resets cursor; skips one turn | Fail open — one context-free turn |
| `GET /channel-context` unavailable | Python SDK catches HTTP error, logs, proceeds without context | Fail open — agent turn continues |
| `add()` throws | Observer catches, logs, returns normally | Qhorus dispatch unaffected |
| Agent not yet associated (pre-Epic 4) | `noAssociation()` — Python SDK skips injection silently | Fail open |

---

## 11. Testing Strategy

| Test class | Module | Framework | What it tests |
|---|---|---|---|
| `ChannelRingBufferTest` | `core` | Pure JUnit 5 | Overflow, `lastEvictionWindowSeq`, TTL, cursor, eviction, `lastActivity` |
| `ChannelContextWindowServiceTest` | `core` | Pure JUnit 5 | Routing, aggregation, `windowSeq` monotonicity, `currentWindowSeq`, restart scenario, concurrency smoke |
| `ChannelContextWindowObserverTest` | `casehub` | Pure JUnit 5 + Mockito | EVENT filtering, exception isolation, scope |
| `ChannelContextWindowResourceTest` | `app` | `@QuarkusTest` + `@InjectMock` | HTTP routing, JSON serialisation, `since=0` default, CDI wiring |

`ChannelContextWindowService` is pure in-memory logic (two `ConcurrentHashMap`s and an
`AtomicLong`). It gains nothing from CDI container startup. Fields set directly in test setup
(package-private access within the same test package). The `@QuarkusTest` in `app/` validates
CDI wiring as a side-effect of its startup.

**Key invariants covered:**

- `EVENT` messages never reach the ring buffer
- Observer exceptions never propagate to Qhorus
- Unknown `agentId` → `200` with `agentHasAssociation=false` (not `404`)
- `windowSeq` is globally monotonic across all channels
- `lastEvictionWindowSeq > since` correctly gates the overflow notice (not cumulative count)
- `currentWindowSeq` restart detection: `since > currentWindowSeq` triggers cursor reset
- Overflow notice and messages are additive — both injected when overflow + messages coexist
- `add()` for an unassociated channel is a silent, lock-free no-op
- `associate()` called twice for the same agent merges channels (does not replace)
- `lastActivity = Instant.EPOCH` for a never-written channel (idle notice fires correctly)

**Restart scenario test:**

1. Add 3 messages to service (windowSeq 1, 2, 3)
2. Query with `since=0` → receives all 3, cursor advances to 3
3. Simulate restart: create a new service instance (AtomicLong resets to 0)
4. Re-associate the agent
5. Add 2 new messages (windowSeq 1, 2 in new instance)
6. Query with `since=3` (stale cursor from old instance)
7. Assert: `currentWindowSeq = 2`, `since (3) > currentWindowSeq (2)` → SDK must reset

---

## 12. Deferred / Out of Scope

| Item | Tracked |
|------|---------|
| WorkerProvisioner calling `associate()` | Issue #4 comment |
| Whether `CaseChannelProvider` should call `associate()` eagerly for owned channels | Epic 4 design decision — timing gap documented in §3 |
| `disassociate(agentId, channelIds)` for case-close cleanup | Future — prevents channel accumulation for deployments that reuse agentIds |
| Optional persistence backend for restart recovery | Future — SPI pattern if needed |
| Cross-channel watch subscriptions (explicit agent-to-channel subscription) | Future — `associate()` API is sufficient; Epic 4 decides which channels to include |
| Cluster-scope `MessageObserver` (multi-JVM) | Future — `LOCAL` is correct for single-JVM; CLUSTER requires Kafka or equivalent |
| Flyway migration for ChannelContextWindow state | **Superseded** — in-memory only by design (see issue #3 comment) |
