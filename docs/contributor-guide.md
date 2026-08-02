# casehub-openclaw -- Contributor Guide

> Internal architecture, SPIs, and extension points for platform builders working on the casehub-openclaw integration tier.

**GitHub:** [casehubio/openclaw](https://github.com/casehubio/openclaw)

---

## Internal Architecture

### OpenClawHookClient

`@ApplicationScoped` CDI bean. `ConcurrentHashMap<String, OpenClawSession>` keyed by `agentId`. `registerSession(agentId, sessionKey, webhookUrl)` called by `WorkerProvisioner` at provision time. `invoke()` catches `WebApplicationException` (Quarkus REST Client behaviour on 5xx -- does not return a `Response`). `Response.close()` called in `finally` block (`jakarta.ws.rs.core.Response` does not implement `AutoCloseable`). `forWebhook()` factory on `AgentInvocationRequest` enforces `deliver=webhook`.

**Known limitation:** Session registry is last-write-wins per `agentId` -- concurrent same-`agentId` workers not supported until `workerId` is available in `WorkResult` (upstream engine enhancement).

**Deferred (verify against live API):** `sessionName` JSON field name; `wakeMode` values for direct-call pattern; `/hooks/wake` body schema.

### ChannelBackend SPI Implementation

Implements the Qhorus `ChannelBackend` SPI to wire bidirectional message flow between a Qhorus channel and an OpenClaw agent. Inbound (CaseHub -> OpenClaw) routes via `/hooks/agent`. Outbound (OpenClaw -> CaseHub) routes via the `deliver:webhook` normaliser.

### DirectCallBridge

Request-reply bridge over async webhooks. Enables synchronous `AgentProvider` and langchain4j `ChatModel` invocations without requiring a persistent OpenClaw session.

**Flow:** Caller -> `OpenClawAgentProvider.invoke()` -> `DirectCallBridge.submit(correlationId)` registers a `CompletableFuture` -> `OpenClawHookClient.invokeDirect()` calls `/hooks/agent` sessionlessly with delivery URL `POST /openclaw/direct-call/{correlationId}` -> OpenClaw processes prompt -> POSTs result to delivery URL -> `DirectCallDeliveryResource` calls `bridge.complete(correlationId, output)` -> future completes -> caller unblocked.

| Class | Module | Role |
|-------|--------|------|
| `OpenClawHookClient.invokeDirect()` | `core` | Sessionless `/hooks/agent` call -- no registered session needed, `sessionKey` is null |
| `DirectCallBridge` | `casehub` | `@ApplicationScoped` in-memory `CompletableFuture<String>` registry keyed by correlationId; auto-removes on completion/timeout |
| `OpenClawAgentProvider` | `casehub` | `AgentProvider` SPI -- orchestrates the bridge flow; emits `Multi<AgentEvent>` with a single `TextDelta`; `openSession()` unsupported (single-shot only) |
| `OpenClawChatModel` | `casehub` | langchain4j `ChatModel` -- extracts system/user prompts from `ChatRequest`, delegates to `AgentProvider`, supports JSON schema via text preamble |
| `DirectCallDeliveryResource` | `app` | `POST /openclaw/direct-call/{correlationId}` (`@PermitAll @Blocking`) -- receives response, completes the future |

### OversightGateService

`OversightGateService` owns the gate lifecycle:

- `evaluate(workChannelId, agentId, output)` -- called by the delivery webhook for every OpenClaw result: archives the agent text output as a non-resolving STATUS message on the work channel. Completion signaling is via MCP tool calls (`casehub_done`, `casehub_reject`, etc.) -- no speech-act classification occurs. `commitmentId` is injected into the COMMAND message by `OpenClawChannelBackend.post()`.
- `fulfill(gateId, rawOutput)` -- called by the oversight delivery webhook when human responds:
  1. Parse approval (first word must be `"approved"`; null/blank -> rejected)
  2. Look up `Commitment` by `correlationId=gateId` (durable -- survives restart)
  3. Dispatch RESPONSE/DECLINE to oversight (closes Commitment) + STATUS to work channel

**Known limitation:** STATUS dispatched to work channel instead of DONE because `inReplyTo` (original COMMAND message ID) is not available at delivery time (openclaw#16).

### ChannelContextWindow -- Two-Phase Association Design

Two-phase binding managed across the `casehub` module SPIs: `bindAgent(agentId, caseId)` is called by `OpenClawWorkerProvisioner` at provision time; `bindChannel(caseId, channelId)` is called by `OpenClawCaseChannelProvider` when the channel is assigned. `ChannelContextWindowService` joins at query time -- no cross-SPI coordination at write time. `unbindAgent()` is called by `OpenClawWorkerStatusListener.onWorkerCompleted()` for cleanup.

### Multi-Tenancy

Tenancy propagation through the provisioner and channel bridge:

- **Composite `AgentKey`** -- `ChannelContextWindowService` uses composite `AgentKey(agentId, tenancyId)` for context window isolation. Same `agentId` from different tenants gets independent context windows.
- **`OpenClawAgentRegistry` tenancy** -- added `caseToTenancy: Map<UUID, String>` (caseId -> tenancyId) for non-request-context tenancyId recovery on the status listener path.
- **Delivery webhook pattern** -- `OpenClawDeliveryResource` uses `@CrossTenant CrossTenantChannelStore.findById()` to resolve tenancyId from the channel entity. Webhook callbacks have no casehub principal. **Protocol: never use tenant-scoped `ChannelService.findById()` in delivery webhook handlers** (PP-20260612-520281).
- **`PluginTokenBridgeMechanism`** -- custom `HttpAuthenticationMechanism`; validates pre-shared bearer token for `/openclaw/plugin/*`; creates `SecurityIdentity` with `openclaw-plugin` role and `casehub.plugin.bridge` attribute; bridge to OIDC client-credentials (openclaw#52). Stamps `tenancyId` as a SecurityIdentity attribute so `SecurityIdentityCurrentPrincipal` resolves tenancy for bridge-authenticated identities.
- **`OpenClawCurrentPrincipal`** -- `@Alternative @Priority(150)` bridges plugin identity; checks `casehub.plugin.bridge` SecurityIdentity attribute from the mechanism, returns plugin values if present, delegates to OIDC otherwise.

---

## Epic Status

- Epic 1 (scaffold): complete -- Maven structure, CLAUDE.md, CI
- Epic 2 (OpenClaw hook API client): complete -- `OpenClawHookClient`, session registry, `deliver:webhook` normaliser
- Epic 3 (ChannelContextWindow service): complete -- in-memory ring buffer, `ChannelContextWindowObserver`, REST endpoint
- Epic 4 (CaseHub SPIs: `WorkerProvisioner`, `ChannelBackend`, `CaseChannelProvider`, `WorkerStatusListener`): complete
- Epic 5 (TypeScript Plugin SDK + Python client library): complete -- `plugin/` (npm), `python/` (PyPI), ADR 0001
- Epic 6 (OversightGateService, oversight delivery endpoint): complete; subsequently simplified in openclaw#28
- Epic 7 (Layer 0 -- Quarkus MCP endpoint, 9 tools, 3 resources, 4 plugin hooks, global SKILL.md files): complete (openclaw#19)
- DirectCallBridge (openclaw#49): complete -- `AgentProvider` SPI + langchain4j `ChatModel` via sessionless request-reply

---

## Depended On By

| Repo | How |
|------|-----|
| `casehub-life` | As `WorkerProvisioner` -- OpenClaw agents as household and care task workers |
| Any application repo | Any application using OpenClaw as its execution layer |

---

## Current State

All core epics (1-7) and the DirectCallBridge (openclaw#49) are complete. The repo provides a fully functional integration tier with hook API client, channel context window, CaseHub SPI implementations, oversight gate lifecycle, MCP endpoint with tools and resources, TypeScript plugin SDK, and Python client library.

---

## Design Documents

- `docs/specs/openclaw-integration.md` -- integration architecture and hook API
- `docs/specs/openclaw-skill-pack.md` -- skill pack structure and routing
- Research spec in `casehubio/parent` -- original scoping analysis
