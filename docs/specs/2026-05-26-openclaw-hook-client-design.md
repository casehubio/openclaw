# OpenClaw Hook API Client — Design Spec

**Date:** 2026-05-26
**Issue:** casehubio/openclaw#2
**Branch:** issue-002-openclaw-hook-client
**Module:** `core/`

---

## 1. Context and Scope

`casehub-openclaw` is the integration tier bridging CaseHub and OpenClaw. It occupies the
same architectural position as Claudony — implementing CaseHub engine SPIs backed by an
external agent runtime — but targets OpenClaw's hook API rather than the Claude CLI.

This spec covers **Epic 2: the OpenClaw hook API client in `core/`**. The client is the
single integration point through which all subsequent SPI implementations (`WorkerProvisioner`,
`ChannelBackend`, etc.) invoke OpenClaw agents. Getting this layer right — typed, tested,
session-aware — is the prerequisite for all later epics.

**In scope:**
- `OpenClawGatewayClient` — `@RegisterRestClient` interface for `POST /hooks/agent` and
  `POST /hooks/wake`
- `OpenClawHookClient` — `@ApplicationScoped` service owning the session registry and
  invocation orchestration
- `BearerTokenRequestFilter` — bearer auth for all outbound requests
- Request/response record types
- Configuration via `@ConfigMapping`
- Integration test: WireMock stub, assert request shape and auth header

**Out of scope (later epics):**
- `WorkerProvisioner`, `CaseChannelProvider`, `WorkerStatusListener` SPI implementations
- `ChannelBackend` SPI implementation
- `MessageObserver` + `ChannelContextWindow` ring buffer and REST endpoint
- Webhook delivery endpoint in `app/`
- Python SDK `before_prompt_build` hook

---

## 2. Architecture

### 2.1 Precedent: Claudony's Session Mapping Pattern

Claudony's `ClaudonyReactiveWorkerProvisioner` establishes the pattern this design follows.
Claudony bridges two identity systems: CaseHub's `roleName` (the worker's logical identity)
and Claudony's internal tmux `sessionId` (a UUID generated per-provisioning). A shared
`@ApplicationScoped` `WorkerSessionMapping` bean holds the in-memory registry. Both the
`WorkerProvisioner` (writes on provision/terminate) and the `WorkerStatusListener` (reads
on lifecycle events) inject it.

The OpenClaw equivalent:
- CaseHub `roleName` ↔ OpenClaw `agentId` + `sessionKey` + `webhookUrl`
- `OpenClawHookClient` is the shared registry, just as `WorkerSessionMapping` is in Claudony
- Later SPI implementations in `casehub/` inject `OpenClawHookClient` for reads and writes

### 2.2 Three Classes in `core/`

```
io.casehub.openclaw.client
├── OpenClawGatewayClient          @RegisterRestClient — raw HTTP interface
├── OpenClawHookClient             @ApplicationScoped — session registry + invocation
├── OpenClawSession                record — per-provisioning session state
├── AgentInvocationRequest         record — POST /hooks/agent body
├── AgentWakeRequest               record — POST /hooks/wake body
├── OpenClawClientConfig           @ConfigMapping — gateway + delivery config
└── BearerTokenRequestFilter       ClientRequestFilter — bearer auth
```

**`OpenClawGatewayClient`** is the raw HTTP surface. It is a MicroProfile REST Client
interface, CDI-injected with `@RestClient`. `@RegisterProvider(BearerTokenRequestFilter.class)`
ensures the auth filter is applied at CDI injection time, avoiding the known Quarkus gotcha
where `@RegisterProvider` is not honoured by `RestClientBuilder.newBuilder()` (GE-20260415-dfa8ba).

**`OpenClawHookClient`** is the only class that `casehub/` module SPI implementations
import from `core/` for agent invocation. It owns:
1. The session registry (`ConcurrentHashMap<String, OpenClawSession>`)
2. Invocation orchestration: session lookup → request construction → `gatewayClient` delegation
3. Error translation: non-2xx or missing session → `OpenClawInvocationException` (unchecked)

**Separation of concerns:** `core/` knows nothing about CaseHub SPIs, Qhorus channels,
or case orchestration. It knows: agentIds, session keys, webhook URLs, and HTTP calls. The
`casehub/` module builds the CaseHub-specific meaning on top.

### 2.3 Call Flow (Preview for Later Epics)

```
WorkerProvisioner.provision()
  → pick agentId from capability map
  → generate sessionKey (UUID)
  → construct webhookUrl = deliveryBaseUrl + "/channel/" + qhorusChannelId
  → hookClient.registerSession(agentId, sessionKey, webhookUrl)
  → return Worker(roleName, capabilities)

OpenClawChannelBackend.post(channelRef, outboundMessage)
  → resolve agentId from channel-to-agentId map (ChannelBackend's own state)
  → hookClient.invoke(agentId, message, model, timeoutSeconds)
    → sessions.get(agentId) → OpenClawSession(sessionKey, webhookUrl)
    → POST /hooks/agent {message, agentId, deliver=webhook, to=webhookUrl, sessionName=sessionKey, …}

OpenClaw executes → POST webhookUrl → Qhorus delivery endpoint → MessageService.dispatch()
```

---

## 3. Request and Response Shapes

### 3.1 `AgentInvocationRequest`

Maps to `POST /hooks/agent`. Fields sourced from the OpenClaw hook API surface documented
in `docs/specs/openclaw-integration.md` §1.2 and the parent research spec §2.1.

```java
public record AgentInvocationRequest(
    String message,           // required — prompt sent to the agent
    String agentId,           // target OpenClaw agent identity
    String deliver,           // always "webhook" for casehub-openclaw
    String to,                // webhook URL — Qhorus delivery endpoint
    String model,             // LLM backend override; null uses OpenClaw default
    int    timeoutSeconds,    // max execution time
    String sessionName        // optional — maps to OpenClaw session_name (Python SDK);
                              // nullable; serialise with @JsonInclude(NON_NULL) so null
                              // fields are omitted from the JSON body (not sent as null)
) {}
```

`deliver` is always `"webhook"` for `casehub-openclaw`. `deliver: "sync"` is explicitly
excluded — CaseHub uses the webhook async model for all in-case invocations. See
`docs/specs/openclaw-integration.md` §2 (Two Invocation Modes).

`sessionName` is forward-compatible. The OpenClaw Python SDK exposes `session_name`; the
HTTP API may already support it (the gateway processes both). If OpenClaw ignores unknown
JSON fields the field is harmless; if it processes it, session continuity across
provisioning activates immediately without code changes. The field is nullable — `null`
serializes to absent in JSON via `@JsonInclude(NON_NULL)`.

### 3.2 `AgentWakeRequest`

Maps to `POST /hooks/wake`. Lightweight nudge — no delivery config, no model override.

```java
public record AgentWakeRequest(
    String agentId,
    String message
) {}
```

### 3.3 `OpenClawSession`

Per-provisioning session state. Immutable record.

```java
public record OpenClawSession(
    String agentId,
    String sessionKey,    // UUID generated at provision time
    String webhookUrl     // constructed by WorkerProvisioner at registration time
) {}
```

---

## 4. Session Management

### 4.1 Registry Design

```java
// key: agentId (OpenClaw agent name, e.g. "finance-agent")
// value: session state for the current active provisioning of that agent
private final ConcurrentHashMap<String, OpenClawSession> sessions = new ConcurrentHashMap<>();
```

**Key choice — agentId, not workerId:**
The registry is keyed by `agentId` because the `ChannelBackend` (later epic) knows the
agentId from the channel-to-agent mapping and needs to invoke `hookClient.invoke(agentId, …)`.
Keying by CaseHub `workerId` would require an additional level of indirection.

**Known limitation:** last-write-wins per `agentId`. If two concurrent CaseHub cases both
provision "finance-agent", the second registration overwrites the first. This is the same
limitation Claudony's `WorkerSessionMapping` carries (documented in its Javadoc: "Assumes
at most one active instance per role"). The full fix requires `workerId` in `WorkResult`
(upstream engine enhancement). For now, same-agentId concurrency is not a supported
deployment scenario.

### 4.2 Registry API

```java
public void registerSession(String agentId, String sessionKey, String webhookUrl)
public void deregisterSession(String agentId)
public Optional<OpenClawSession> findSession(String agentId)
```

`registerSession` and `deregisterSession` are called by `WorkerProvisioner` (later epic).
`findSession` is available for `ChannelBackend` and `WorkerStatusListener` (later epics)
if they need to inspect session state.

### 4.3 Session Lifecycle

| Event | Who calls | Effect on registry |
|---|---|---|
| `WorkerProvisioner.provision()` | Engine | `registerSession(agentId, sessionKey, webhookUrl)` |
| `WorkerProvisioner.terminate()` | Engine | `deregisterSession(agentId)` |
| JVM restart | — | Registry cleared (in-memory only) |

No persistence. A restart requires re-provisioning via the engine's normal worker
lifecycle. This is consistent with Claudony's approach (tmux sessions do not survive
restarts either).

---

## 5. Invocation

### 5.1 `invoke()`

```java
public void invoke(String agentId, String message, @Nullable String model, int timeoutSeconds) {
    OpenClawSession session = sessions.get(agentId);
    if (session == null) {
        throw new OpenClawInvocationException(
            "No session registered for agentId: " + agentId);
    }
    String effectiveModel = model != null ? model : config.agent().defaultModel();
    int effectiveTimeout = timeoutSeconds > 0 ? timeoutSeconds : config.agent().defaultTimeoutSeconds();
    AgentInvocationRequest request = new AgentInvocationRequest(
        message, agentId, "webhook", session.webhookUrl(),
        effectiveModel, effectiveTimeout, session.sessionKey());
    Response response = gatewayClient.invokeAgent(request);
    if (response.getStatus() / 100 != 2) {
        throw new OpenClawInvocationException(
            "OpenClaw /hooks/agent returned " + response.getStatus() + " for agentId: " + agentId);
    }
}
```

`OpenClawInvocationException` is unchecked. The caller (`ChannelBackend` in `casehub/`)
maps it to the appropriate SPI error behaviour.

### 5.2 `wake()`

```java
public void wake(String agentId, String message) {
    AgentWakeRequest request = new AgentWakeRequest(agentId, message);
    Response response = gatewayClient.wakeAgent(request);
    if (response.getStatus() / 100 != 2) {
        throw new OpenClawInvocationException(
            "OpenClaw /hooks/wake returned " + response.getStatus() + " for agentId: " + agentId);
    }
}
```

`wake()` does not require a registered session — it is a lightweight nudge and does not
carry a webhook delivery URL. The agentId is sufficient.

---

## 6. Authentication

**Mechanism:** bearer token in `Authorization` header, set by `BearerTokenRequestFilter`
on every outbound request.

```java
@ApplicationScoped
public class BearerTokenRequestFilter implements ClientRequestFilter {
    private final String token;

    @Inject
    public BearerTokenRequestFilter(OpenClawClientConfig config) {
        this.token = config.gateway().bearerToken();
    }

    @Override
    public void filter(ClientRequestContext ctx) {
        ctx.getHeaders().putSingle("Authorization", "Bearer " + token);
    }
}
```

Registered via `@RegisterProvider(BearerTokenRequestFilter.class)` on the
`@RegisterRestClient` interface. The filter must be `@ApplicationScoped` — Quarkus uses CDI
injection when the provider is a CDI bean, which is required for `@Inject` to resolve
`OpenClawClientConfig`. The `@Provider` annotation is a JAX-RS server-side marker and must
not appear here. CDI injection (not `RestClientBuilder`) is intentional (GE-20260415-dfa8ba).

Token is static per deployment, read from config at startup. No per-request token
variation at this stage.

---

## 7. Configuration

```java
@ConfigMapping(prefix = "casehub.openclaw")
public interface OpenClawClientConfig {

    Gateway gateway();
    Delivery delivery();
    Agent agent();

    interface Gateway {
        @WithName("url")
        String url();                         // OpenClaw gateway base URL
        @WithName("bearer-token")
        String bearerToken();                 // Authorization: Bearer <token>
    }

    interface Delivery {
        @WithName("base-url")
        String baseUrl();                     // webhook delivery base URL
    }

    interface Agent {
        @WithName("default-model")
        @WithDefault("claude-opus-4-5")
        String defaultModel();
        @WithName("default-timeout-seconds")
        @WithDefault("120")
        int defaultTimeoutSeconds();
    }
}
```

**`delivery.base-url`** is the prefix from which `WorkerProvisioner` (later epic) constructs
the `to` URL: `{delivery.base-url}/channel/{qhorusChannelId}`. The `core/` module does not
construct webhook URLs — it stores and sends whatever `webhookUrl` is registered. The
construction is the WorkerProvisioner's responsibility.

Example `application.properties`:
```properties
casehub.openclaw.gateway.url=https://openclaw-gateway.internal
casehub.openclaw.gateway.bearer-token=${OPENCLAW_BEARER_TOKEN}
casehub.openclaw.delivery.base-url=https://casehub.internal/openclaw/delivery
casehub.openclaw.agent.default-model=claude-opus-4-5
casehub.openclaw.agent.default-timeout-seconds=120
```

The gateway REST client uses Quarkus MicroProfile REST Client config:
```properties
quarkus.rest-client.openclaw-gateway.url=${casehub.openclaw.gateway.url}
```

---

## 8. Integration Test

### 8.1 Approach

`@QuarkusTest` with WireMock (via `quarkus-junit5-wiremock` or `WireMockServer` started
in `@BeforeAll`). The REST client is configured to point at the WireMock port.

**Garden entry GE-20260427-7162b2 applies:** a self-referencing `@QuarkusTest` REST client
silently hits the default port (8080), not the test port. Configure explicitly:
```properties
# in application.properties or test profile
%test.quarkus.rest-client.openclaw-gateway.url=http://localhost:${quarkus.http.test-port}
```
For WireMock (a separate server, not the Quarkus test server), use the WireMock port
directly in test setup.

### 8.2 Test Cases

```
OpenClawHookClientTest (pure unit — no @QuarkusTest needed for session logic):
  - registerSession then findSession → returns registered session
  - deregisterSession → findSession returns empty
  - invoke with registered session → delegates to gateway client with correct payload
  - invoke with no session → throws OpenClawInvocationException
  - wake → delegates to gateway client (no session required)

OpenClawGatewayClientIntegrationTest (@QuarkusTest + WireMock):
  - POST /hooks/agent → WireMock stub returns 200
    → assert request body: agentId, message, deliver=webhook, to=webhookUrl, model, timeoutSeconds
    → assert Authorization: Bearer <configured-token> header present
  - POST /hooks/agent → WireMock stub returns 500
    → assert OpenClawInvocationException thrown
  - POST /hooks/wake → WireMock stub returns 200
    → assert request body: agentId, message
    → assert Authorization header present
```

### 8.3 Test Configuration

```properties
# src/test/resources/application.properties
casehub.openclaw.gateway.url=http://localhost:${wiremock.port}
casehub.openclaw.gateway.bearer-token=test-bearer-token
casehub.openclaw.delivery.base-url=https://casehub.test/openclaw/delivery
casehub.openclaw.agent.default-model=claude-haiku-4-5-20251001
casehub.openclaw.agent.default-timeout-seconds=30
```

---

## 9. Platform Coherence

**Capability ownership:** `casehub-openclaw` is the sole owner of `OpenClawHookClient` and
all HTTP integration with the OpenClaw gateway. No other repo reaches the OpenClaw HTTP API.

**Cross-repo dependency table:** the `casehub-openclaw-core` artifact will be consumed by
`casehub-openclaw-casehub` (SPI implementations) and `casehub-openclaw-app`. Both are
already in the parent POM's `<dependencyManagement>`. No new cross-repo dependencies are
introduced — `core/` depends only on `casehub-qhorus-api`, `casehub-engine-api`,
`casehub-platform-api`, and Quarkus CDI + REST client (all already in the parent POM).

**Module tier rules:** `core/` is a library module — no Quarkus build goal, Jandex index
required for CDI bean discovery (already configured in `core/pom.xml`). `OpenClawHookClient`
is `@ApplicationScoped`; it will be discovered via the Jandex index at augmentation time.

**`casehub-platform` scope rule (PP-20260524-a8f597):** `casehub-platform` is not a
dependency of `core/` — the mock `@DefaultBean`s are not needed here (no CDI wiring that
requires them in a library module). The scope rule is satisfied.

**Auth retrofit readiness:** `OpenClawHookClient` carries no `CurrentPrincipal` references.
The bearer token is a deployment credential, not a user principal. The SPI layer (later epics)
is where `CurrentPrincipal` context would propagate to OpenClaw calls if needed.

---

## 10. Open Questions

These are deferred — not blocking for Epic 2, but must be addressed before the relevant
later epic:

1. **`sessionName` in HTTP API:** does `/hooks/agent` accept a `sessionName` (or equivalent)
   field that maps to the Python SDK's `session_name`? Verify against OpenClaw gateway source
   or live API before the `WorkerProvisioner` epic (Epic 4). If unsupported, remove the field
   from `AgentInvocationRequest`; if supported, leave it as-is.

2. **Concurrent same-agentId workers:** the last-write-wins limitation is documented. The
   fix (workerId in `WorkResult`) requires an upstream engine change. File a tracking issue
   on `casehubio/engine` before completing the `WorkerProvisioner` epic.

3. **OpenClaw `/tools/invoke` endpoint:** the research spec (§12.9) notes an undocumented
   `/tools/invoke` endpoint. Investigate before Epic 4 — if it enables direct skill invocation
   by name, it strengthens the direct call pattern and may warrant a third method on
   `OpenClawGatewayClient`.

4. **`wake()` and sessions:** currently `wake()` does not require a registered session. If
   OpenClaw's wake semantics require a session identifier, revisit this decision.
