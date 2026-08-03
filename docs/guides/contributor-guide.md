# casehub-openclaw -- Contributor Guide

> Internal architecture, SPIs, extension points, and implementation details for platform builders working on the casehub-openclaw integration tier.

**GitHub:** [casehubio/openclaw](https://github.com/casehubio/openclaw)

---

## Internal Architecture

### OpenClawHookClient

`@ApplicationScoped` CDI bean in `core`. Wraps the `OpenClawGatewayClient` MicroProfile REST Client. Maintains a `ConcurrentHashMap<String, OpenClawSession>` keyed by `agentId` for session-based invocations.

**Session registry:**
- `registerSession(agentId, sessionKey, webhookUrl)` -- called by `OpenClawChannelBackend.post()` before each invocation (not at provision time)
- `deregisterSession(agentId)` -- explicit cleanup
- `findSession(agentId)` -- optional lookup

**Invocation methods:**

| Method | Session required | Use case |
|--------|-----------------|----------|
| `invoke(agentId, message, model, timeoutSeconds)` | Yes | Standard case step invocation -- delivery URL taken from registered session's `webhookUrl` |
| `invoke(agentId, message, model, timeoutSeconds, deliveryUrl)` | Yes (for sessionKey) | Explicit delivery URL override -- used by `OversightGateService` for gate invocations |
| `invokeDirect(agentId, message, model, timeoutSeconds, deliveryUrl)` | No | Sessionless invocation for DirectCallBridge -- `sessionKey` is null, auth via gateway bearer token |
| `wake(agentId, message)` | No | Wake a dormant agent -- heartbeat-initiated flows, not case steps |

**Error handling:** Quarkus Reactive REST Client throws `WebApplicationException` (specifically `ClientWebApplicationException`) for non-2xx responses when return type is `Response`. All invocation methods catch this and re-wrap as `OpenClawInvocationException` for a stable exception contract. `Response.close()` is called in `finally` (`jakarta.ws.rs.core.Response` does not implement `AutoCloseable`).

**Configuration via `OpenClawClientConfig`:**
- `casehub.openclaw.gateway.url` -- OpenClaw gateway base URL
- `casehub.openclaw.agent.default-model` -- fallback Claude model
- `casehub.openclaw.agent.default-timeout-seconds` -- fallback timeout
- `casehub.openclaw.delivery.base-url` -- base URL for delivery webhook construction
- `casehub.openclaw.delivery.token` -- optional shared token appended as `?token=` query param

---

### ChannelBackend SPI Implementation

`OpenClawChannelBackend` implements the Qhorus `ChannelBackend` SPI to route COMMANDs from Qhorus channels to OpenClaw agents.

**Registration:** Self-registers with `ChannelGateway` by observing `ChannelInitialisedEvent` for channels whose name starts with `CaseChannel.CASE_CHANNEL_PREFIX`. This also handles startup recovery: Qhorus fires `ChannelInitialisedEvent` for all persisted channels at startup, so the backend re-registers without its own recovery logic.

**Message routing:**
- Only COMMAND messages invoke the agent. All other types are silently ignored -- they are already stored in the ring buffer by `ChannelContextWindowObserver` and in the Qhorus ledger.
- **Target-based routing (openclaw#70):** When a COMMAND carries a non-blank `target` field, the backend routes to that specific agentId. It validates the target agent is registered for the same caseId. If the target is unknown or belongs to a different case, the COMMAND is dropped with a warning.
- **Fallback routing:** Without a target, `registry.findAgentId(caseId)` picks an arbitrary registered agent. If multiple agents are registered (1:N), this logs a warning.

**Commitment context injection (ADR-0004):** When a COMMAND carries a `correlationId` (= commitmentId), `buildPrompt()` appends a fully-resolved commitment context block to the message content. The block contains the agentId and commitmentId as concrete values (not template variables) plus the full MCP tool call signatures for `casehub_done`, `casehub_reject`, `casehub_checkpoint`, `casehub_escalate`, `casehub_delegate`, and `casehub_block`.

**Delivery guarantee:** `DeliveryGuarantee.AT_LEAST_ONCE` (openclaw#57).

**Delivery token:** `appendDeliveryToken()` appends `?token={token}` to the webhook URL when `casehub.openclaw.delivery.token` is configured.

---

### OpenClawAgentRegistry (1:N Support)

`@ApplicationScoped` CDI bean. Four concurrent maps provide the routing backbone:

```
agentToCase:       agentId   -> caseId        (1:1)
caseToAgents:      caseId    -> Set<agentId>  (1:N since openclaw#63)
agentToSessionKey: agentId   -> sessionKey    (1:1)
caseToTenancy:     caseId    -> tenancyId     (1:1)
```

**Register:** `register(agentId, tenancyId, caseId, sessionKey)` -- if the agentId was previously registered under a different caseId, the old mapping is cleaned up. The `caseToAgents` entry is removed when its agent set becomes empty.

**Deregister:** `deregister(agentId)` returns `DeregistrationResult(caseId, wasLastAgent)`. The `wasLastAgent` flag tells `OpenClawWorkerStatusListener` whether to close the case's context window.

**Lookup methods:**
- `findAgentId(UUID caseId)` -- transitional single-agent lookup; logs warning when multiple agents registered
- `findAgentIds(UUID caseId)` -- returns immutable copy of the full agent set
- `findCaseId(String agentId)` -- reverse lookup
- `findSessionKey(String agentId)` -- session key for invocation
- `findTenancyId(UUID caseId)` -- tenancyId recovery for non-request-context paths
- `hasAgentsForCase(UUID caseId)` -- fast existence check

---

### OpenClawAgentConfigResolver

`@ApplicationScoped` CDI bean. Merges agent configuration from two sources:

1. **`ProvisionerConfigRegistry`** (from `casehub-ops`/`casehub-engine-api`) -- deployment-time config stored by `DeploymentProviderConfigStore`. Provider name: `"openclaw"`. Required key: `sessionKey`. Optional key: `capabilities` (List or String).
2. **`OpenClawCasehubConfig`** -- Quarkus `@ConfigMapping` for local development. Maps `casehub.openclaw.agents.{agentId}.session-key` and `.capabilities`.

Registry entries take precedence over local config (later put wins in the merged map). `onStartup()` validates all registry entries at application start.

`configFor(agentId)` returns `AgentConfig(sessionKey, capabilities)`. Throws `IllegalArgumentException` if neither source has a config for the requested agentId.

---

### DirectCallBridge

`@ApplicationScoped` CDI bean. Request-reply bridge over async webhooks using `ConcurrentHashMap<String, CompletableFuture<String>>` keyed by correlationId.

**Lifecycle:**
1. `submit(correlationId, timeout)` -- registers a `CompletableFuture` with `orTimeout()`. Self-evicting: `whenComplete()` removes the entry on completion, timeout, or cancellation. Returns existing future if correlationId is already registered (dedup guard with warning).
2. `complete(correlationId, responseText)` -- completes the future. Called by `DirectCallDeliveryResource` when OpenClaw posts the result.
3. `cancel(correlationId)` -- cancels the future. Called by `OpenClawAgentProvider` on `Multi` termination.

**DirectCallDeliveryResource:** `POST /openclaw/direct-call/{correlationId}` (`@PermitAll @Blocking`). Receives the OpenClaw response text and calls `bridge.complete()`. Validates delivery token via `DeliveryTokenValidator`.

---

### OpenClawAgentProvider

Implements the `AgentProvider` SPI from `casehub-platform-agent-api`. Not a CDI bean -- instantiated directly with constructor parameters (bridge, hookClient, agentId, deliveryBaseUrl, deliveryToken).

**`invoke(AgentSessionConfig)`:** Emits `Multi<AgentEvent>` with a single `TextDelta` event. Orchestrates the DirectCallBridge flow: generates correlationId -> submits future -> constructs delivery URL with correlationId and optional token -> calls `hookClient.invokeDirect()` -> awaits future completion. Registers `onTermination` handler to cancel the bridge future if the subscription is cancelled.

**`openSession()`:** Throws `UnsupportedOperationException` -- DirectCallBridge is single-shot only.

---

### OpenClawChatModel

Implements langchain4j `ChatModel` interface. Wraps an `AgentProvider` to present OpenClaw as a standard chat model.

**`doChat(ChatRequest)`:**
1. Extracts system prompt from `SystemMessage` entries
2. Extracts last user text from `UserMessage` entries
3. If `ResponseFormat` contains a `JsonSchema`, serializes the schema as a text preamble prepended to the user prompt (OpenClaw agents do not support native JSON mode)
4. Delegates to `agentProvider.invoke()` with combined prompt
5. Collects all `TextDelta` events into a single response string
6. Validates JSON output when a JSON schema was requested
7. Returns `ChatResponse` with `AiMessage`

**Schema serialization:** `serializeSchema()` renders `JsonObjectSchema` properties as `"fieldName": type (required),` lines. Type names are derived from the schema element class name (e.g., `JsonStringSchema` -> `string`).

---

### OversightGateService

`@ApplicationScoped` CDI bean implementing `io.casehub.api.spi.OversightGateService`. Owns the full oversight gate lifecycle.

**Dependencies:**
- `ChannelService`, `MessageService`, `CommitmentStore` -- Qhorus services for channel/message/commitment operations
- `OversightGateDispatcher` -- atomically dispatches approval/rejection messages
- `Instance<ActionRiskClassifier>` qualified with `@RiskClassifier` -- CDI-injected risk classifiers
- `CrossTenantMessageStore`, `CrossTenantChannelStore` -- cross-tenant reads for webhook paths that have no casehub principal

**`evaluate(workChannelId, tenancyId, agentId, output)`:**
Archives the agent's webhook output as a non-resolving STATUS message on the work channel. No completion signaling occurs. Null/blank output is silently skipped. Null tenancyId logs a warning and skips dispatch. All exceptions caught and logged.

**`openGate(agentId, commitmentId, outcome, tenancyId)`:**
1. Looks up the commitment by correlationId. If no channel-backed commitment exists, returns `Autonomous` (fail-open).
2. Extracts caseId from the work channel name via `CaseChannelNames`.
3. Classifies the proposed action: creates `PlannedAction.of(outcome, "COMPLETION")`, builds `ClassificationContext`, calls `classifyMostRestrictive()`.
4. **Classification logic:** Iterates all `ActionRiskClassifier` CDI beans. If none are registered (`classifiers.isUnsatisfied()`), returns `Autonomous`. Uses most-restrictive-wins strategy: `GateRequired` always wins over `Autonomous`. Between two `GateRequired` decisions, prefers the one with `candidateGroups`, then the shorter `expiresIn`. On classifier exception: returns `GateRequired` with "Classifier error" reason (fail-safe -- failure is not safe).
5. If `GateRequired`: looks up the oversight channel by name (`CaseChannelNames.oversightChannelName(caseId)`). Dispatches a COMMAND to the oversight channel with gate context serialized as Java Properties in the message content (commitmentId, workChannelId, commandMessageId, tenancyId). Returns `GateOutcome.GatePending(gateId, reason)`.
6. Fail-open throughout: missing commitment, missing channel, missing COMMAND message, dispatch error -> all return `Autonomous`.

**`fulfill(gateId, rawOutput)`:**
1. Uses `CrossTenantMessageStore.scan()` to locate the gate COMMAND by correlationId=gateId (cross-tenant -- delivery webhook has no casehub principal).
2. Deserializes `GateContext` from the COMMAND message content (Java Properties format).
3. Recovers tenancyId from gate context; falls back to `CrossTenantChannelStore.findById()` for pre-#29 gates that lack tenancyId in the content.
4. Parses approval: first word of rawOutput must be "approved" (case-insensitive, punctuation stripped). Null/blank is treated as rejected.
5. Delegates to `OversightGateDispatcher.dispatch()` for atomic message dispatching.

**`GateContext` record:** `(originalCommitmentId, workChannelId, commandMessageId, tenancyId)`. Serialized as Java Properties into the oversight COMMAND message content for crash-safe persistence via Qhorus.

---

### CaseChannelProvider Implementation

`OpenClawCaseChannelProvider` implements `CaseChannelProvider` SPI. Creates and manages Qhorus channels per CaseHub case.

**Channel topology:** Three normative channels per case (work, observe, oversight), all APPEND semantic. Channel specs are defined by `NormativeChannelLayout` from `casehub-engine-api`. Channel names follow the convention `case-{caseId}/{purpose}`.

**`openChannel(caseId, purpose)`:** Idempotent -- finds existing channel by name before creating. `gateway.initChannel()` is called on new channels only. `contextService.bindChannel()` is called after each open to register with ChannelContextWindow.

**`postToChannel()`:** Dispatches a message to the named channel via `MessageService.dispatch()`.

**`listChannels(caseId)`:** Finds all channels with the `case-{caseId}/` name prefix.

---

### WorkerProvisioner Implementation

`OpenClawWorkerProvisioner` implements `WorkerProvisioner` SPI.

**`provision(capabilities, context)`:**
1. Resolves agentId from capabilities via `OpenClawAgentConfigResolver` (subset match -- agent must cover all requested capabilities)
2. Gets sessionKey from the agent config
3. Registers in `OpenClawAgentRegistry` with agentId, tenancyId (from `CurrentPrincipal`), caseId, sessionKey
4. Binds agent to `ChannelContextWindowService`
5. Returns `ProvisionResult.empty()` -- no worker process to start (OpenClaw agents are always-running)

**`terminate(workerId, tenancyId)`:** Deregisters from registry and unbinds from ChannelContextWindow.

**`getCapabilities()`:** Returns the union of all configured agents' capabilities.

---

### WorkerStatusListener Implementation

`OpenClawWorkerStatusListener` implements `WorkerStatusListener` SPI.

**`onWorkerCompleted(workerId, result)`:**
1. Deregisters from `OpenClawAgentRegistry` (returns `DeregistrationResult`)
2. Unbinds agent from `ChannelContextWindowService`
3. If this was the last agent for the case (`wasLastAgent`), calls `contextService.closeCase(caseId)` to release all channel associations and ring buffers

**`onWorkerStalled(workerId)`:** Fires `WorkerStalledEvent` CDI event. Agent remains registered -- Watchdog drives recovery.

---

### ChannelContextWindowObserver

`@ApplicationScoped` CDI bean implementing `MessageObserver` SPI. Feeds every dispatched Qhorus message into the `ChannelContextWindowService` ring buffer.

- EVENT messages excluded -- `MessageType.isAgentVisible()` returns false for EVENT
- Never queries Qhorus state (dispatcher fires before enclosing transaction commits)
- Never propagates exceptions (per MessageObserver SPI contract)

---

### Scenario Infrastructure (Demo UI)

The scenario subsystem provides a demo execution framework for showcasing multi-agent workflows.

**`casehub` module classes:**

| Class | Role |
|-------|------|
| `ScenarioDef` | Record: `id`, `name`, `description`, `agents` (list of `AgentDef`), `gateAgentId`, `caseId` |
| `AgentDef` | Record: `agentId`, `role`, `capabilities` |
| `ScenarioStateStore` | In-memory state store for scenario execution. Tracks scenario status, agent states, channel messages, commitments, and pending gates. Thread-safe via concurrent collections. Broadcasts typed `CaseExecutionEvent` objects to registered listeners. |
| `ScenarioObserver` | `MessageObserver` SPI implementation. Routes Qhorus messages from channels registered to scenarios into `ScenarioStateStore`. Detects gate-pending (gate agent COMMAND) and gate-resolved (gate agent RESPONSE/DECLINE) events. |
| `ScenarioMetadataProvider` | Provides scenario definitions |
| `ScenarioEventListener` | Functional interface for typed event listeners |
| `CaseExecutionEvent` | Sealed hierarchy: `ScenarioStartedEvent`, `ScenarioCompletedEvent`, `ScenarioFailedEvent`, `AgentStartedEvent`, `AgentCompletedEvent`, `ChannelMessageEvent`, `CommitmentUpdatedEvent`, `GatePendingEvent`, `GateResolvedEvent` |
| `ScenarioStateSnapshot` | Read-only snapshot of scenario state for REST/SSE consumers |
| `GateState` | Record: pending gate state with `gateId`, `agentId`, `action`, `classification`, `priorAgentsJson` |
| `AgentState` | Record: `agentId`, `role`, `state`, `durationMs` |

**`app` module classes:**

| Class | Role |
|-------|------|
| `ScenarioExecutionService` | Orchestrates scenario lifecycle: provisions agents, opens channels, dispatches initial COMMANDs |
| `ScenarioRestResource` | REST API: `GET /api/scenarios` (list), `GET /api/scenarios/{id}/state` (snapshot), `POST /api/scenarios/{id}/start` (execute), `PUT /api/scenarios/{id}/workitems/{gateId}/complete` (gate resolution) |
| `ScenarioSseResource` | SSE endpoint for real-time scenario state streaming |
| `DemoGateClassifier` | `ActionRiskClassifier` implementation for demo scenarios |
| `ExampleController`, `ExamplePoller`, `ExampleSetup` | Demo scenario wiring and lifecycle |

---

### Security Architecture

**Authentication mechanisms:**

| Path | Mechanism | Identity |
|------|-----------|----------|
| `/openclaw/plugin/*`, `/channel-context/*` | `PluginTokenBridgeMechanism` | `openclaw-plugin` principal with `openclaw-plugin` role. Timing-safe token comparison via `MessageDigest.isEqual()`. Creates `SecurityIdentity` with `casehub.plugin.bridge` attribute and hardcoded default tenancyId. |
| `/openclaw/delivery/*` | `@PermitAll` + `DeliveryTokenValidator` | No casehub identity. Webhook callbacks from OpenClaw carry no OIDC token -- auth is structurally impossible. Delivery token validated at the resource level. |
| `/mcp` | OIDC / HTTP security policy | Standard casehub principal via `casehub-platform-oidc` |
| `/api/scenarios` (read) | `@PermitAll` | Anonymous access for demo UI |
| `/api/scenarios` (write) | `@RolesAllowed(ADMIN)` | Admin-only for scenario execution and gate resolution |

**`PluginTokenBridgeMechanism`:** Custom `HttpAuthenticationMechanism`. Returns `Uni.createFrom().nullItem()` for non-plugin paths (does not interfere with OIDC). `getCredentialTransport()` returns null to avoid conflicting with OIDC's Bearer transport declaration. Path guard in `authenticate()` provides isolation instead. Configured via `casehub.openclaw.plugin.bearer-token`.

**Delivery token:** `DeliveryTokenValidator` validates the `?token=` query parameter on delivery webhook endpoints. Separate from the plugin bearer token -- delivery endpoints use query-param auth because OpenClaw webhook callbacks cannot set custom HTTP headers.

**Multi-tenancy in delivery paths:** Delivery webhooks have no casehub principal. `OpenClawDeliveryResource` uses `@CrossTenant CrossTenantChannelStore.findById()` to resolve tenancyId from the channel entity. Protocol PP-20260612-520281: never use tenant-scoped `ChannelService.findById()` in delivery webhook handlers.

---

## CaseHub SPI Summary

| SPI | Implementation | Module |
|-----|----------------|--------|
| `WorkerProvisioner` | `OpenClawWorkerProvisioner` | `casehub` |
| `CaseChannelProvider` | `OpenClawCaseChannelProvider` | `casehub` |
| `WorkerStatusListener` | `OpenClawWorkerStatusListener` | `casehub` |
| `ChannelBackend` | `OpenClawChannelBackend` | `casehub` |
| `MessageObserver` | `ChannelContextWindowObserver` | `casehub` |
| `MessageObserver` | `ScenarioObserver` | `casehub` |
| `AgentProvider` | `OpenClawAgentProvider` | `casehub` |
| `OversightGateService` | `OversightGateService` | `casehub` |

---

## Key Protocols

| Protocol | ID | Summary |
|----------|----|---------|
| Delivery webhook cross-tenant reads | PP-20260612-520281 | Never use tenant-scoped `ChannelService.findById()` in delivery webhook handlers; use `@CrossTenant CrossTenantChannelStore` |
| Gate context sentinel guard | PP (local) | Guard against null `GateContext` in `fulfill()` -- pre-#29 gates may lack tenancyId |
| Gate fail-open asymmetry | PP (local) | Infrastructure failures -> Autonomous (fail-open); classifier failures -> GateRequired (fail-safe) |
| MCP tool no-instance cache | PP (local) | MCP tools must not cache CDI Instance lookups -- they are re-evaluated per call |
| OIDC CDI Qhorus exclusion | PP-20260623-c3244e | Qhorus SPI beans must be excluded from OIDC augmentation to prevent circular dependency |

---

## Epic Status

- Epic 1 (scaffold): complete -- Maven structure, CLAUDE.md, CI
- Epic 2 (OpenClaw hook API client): complete -- `OpenClawHookClient`, session registry, `deliver:webhook` normaliser
- Epic 3 (ChannelContextWindow service): complete -- in-memory ring buffer, `ChannelContextWindowObserver`, REST endpoint
- Epic 4 (CaseHub SPIs: `WorkerProvisioner`, `ChannelBackend`, `CaseChannelProvider`, `WorkerStatusListener`): complete
- Epic 5 (TypeScript Plugin SDK + Python client library): complete -- `plugin/` (npm), `python/` (PyPI), ADR 0001
- Epic 6 (OversightGateService, oversight delivery endpoint): complete; subsequently simplified in openclaw#28
- Epic 7 (Layer 0 -- Quarkus MCP endpoint, 10 tools, 2 resources, 4 plugin hooks, global SKILL.md files): complete (openclaw#19)
- Multi-tenancy (openclaw#29): complete -- tenancyId propagation through provisioner, channel bridge, delivery, and gates
- DirectCallBridge (openclaw#49): complete -- `AgentProvider` SPI + langchain4j `ChatModel` via sessionless request-reply
- Auth hardening (openclaw#41-44, #51-54): complete -- OIDC wiring, plugin token mechanism, delivery token validation, MCP endpoint auth
- Demo UI (openclaw#58): complete -- Lit Web Components, SSE, scenario execution
- Agent config (openclaw#36, #56): complete -- `ProvisionerConfigRegistry` integration
- 1:N agent support (openclaw#63): complete -- `OpenClawAgentRegistry` supports multiple agents per case
- Parallel COMMAND routing (openclaw#70): complete -- target-based routing in `ChannelBackend`
- Virtual thread migration (openclaw#74): complete -- reactive SPI implementations deleted, blocking SPIs only

---

## Open Issues

| Issue | Title | Notes |
|-------|-------|-------|
| #18 | Track: OpenClaw #60209 -- after_tool_call hook not firing in embedded agent runs | Upstream dependency |
| #52 | Migrate plugin auth from bridge token to OIDC client-credentials | Future auth improvement |
| #75 | Fix: Quarkus augmentation failure -- SignalReceivedEventHandler unsatisfied dependency | Build issue |

---

## Depended On By

| Repo | How |
|------|-----|
| `casehub-life` | As `WorkerProvisioner` -- OpenClaw agents as household and care task workers |
| Any application repo | Any application using OpenClaw as its execution layer |

---

## Design Documents

**ADRs:** `docs/adr/` (see ADR Index above)

**Specs:** `docs/specs/` contains dated design specifications for each feature:
- `openclaw-integration.md` -- original integration architecture
- `openclaw-skill-pack.md` -- skill pack structure and routing (epic 7)
- Dated specs for each feature: hook client, context window, SPI design, Python SDK, bidirectional wiring, speech act classification, tenancy propagation, hardening, plugin auth, config registry, demo UI, blocks migration, agent registry 1:N, parallel COMMAND routing, Playwright E2E

**Protocols:** `docs/protocols/casehub/` -- delivery-webhook-cross-tenant-reads, gate-context-sentinel-guard, gate-fail-open-asymmetry, mcp-tool-no-instance-cache, oidc-cdi-qhorus-exclusion
