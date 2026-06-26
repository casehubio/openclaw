# Hardening, Config, and Auth — Design Spec

**Issues:** openclaw#50, openclaw#36, openclaw#43
**Branch:** `issue-50-hardening-config-auth`
**Date:** 2026-06-26

---

## §1 DirectCallBridge Hardening (#50)

### 1.1 Self-evicting futures

`DirectCallBridge.submit()` takes a `Duration timeout` parameter and arms the future
with `CompletableFuture.orTimeout()`. A `whenComplete` callback removes the map entry
on any terminal state (success, timeout, cancellation). This is the single cleanup
mechanism — the explicit `futures.remove()` calls in `complete()` and `cancel()` are
removed to avoid a dual-path contract.

```java
public CompletableFuture<String> submit(String correlationId, Duration timeout) {
    CompletableFuture<String> future = new CompletableFuture<>();
    CompletableFuture<String> existing = futures.putIfAbsent(correlationId, future);
    if (existing != null) {
        log.warnf("Duplicate correlationId=%s — returning existing future", correlationId);
        return existing;
    }
    future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    future.whenComplete((result, error) -> futures.remove(correlationId));
    return future;
}

public void complete(String correlationId, String responseText) {
    CompletableFuture<String> future = futures.get(correlationId);
    if (future != null) {
        future.complete(responseText);
    }
    // whenComplete callback handles map removal
}

public void cancel(String correlationId) {
    CompletableFuture<String> future = futures.get(correlationId);
    if (future != null) {
        future.cancel(true);
    }
    // whenComplete callback handles map removal
}
```

Caller-side change in `OpenClawAgentProvider.invoke()`:

```java
// Current:
var future = bridge.submit(correlationId);

// New — pass timeout from AgentSessionConfig with fallback:
Duration effectiveTimeout = config.timeout() != null
        ? config.timeout() : Duration.ofSeconds(120);
var future = bridge.submit(correlationId, effectiveTimeout);
```

The `onTermination(() -> bridge.cancel(correlationId))` in `OpenClawAgentProvider`
remains as belt-and-suspenders for subscriber cancellation before timeout.

### 1.2 Module placement

Move `DirectCallDeliveryResource`, `DirectCallDeliveryPayload`, and
`DirectCallDeliveryResourceTest` from `casehub/` to `app/`. The resource is the only
`@Path` endpoint in `casehub/` — every other REST resource lives in `app/`. The resource
only CDI-injects `DirectCallBridge` (which stays in `casehub/`); no tight coupling
justifies the boundary violation.

Package: `io.casehub.openclaw.app` (alongside `OpenClawDeliveryResource`,
`OpenClawOversightDeliveryResource`, etc.).

### 1.3 Dead field removal

Remove `agentId` from `DirectCallDeliveryPayload`. It is declared but never read by
`DirectCallDeliveryResource`. The payload becomes:

```java
public record DirectCallDeliveryPayload(String output) {}
```

### 1.4 Security

`DirectCallDeliveryResource` keeps `@PermitAll` — it receives webhook callbacks from
OpenClaw, consistent with the other delivery endpoints. Add a security test in
`OpenClawRestSecurityTest` for the endpoint at its new `app/` location.

---

## §2 Agent Provider Config from DeploymentProviderConfigStore (#36)

### 2.1 SPI in `casehub/`

```java
public interface AgentProviderConfigSource {
    record AgentConfig(String sessionKey, List<String> capabilities) {}
    Map<String, AgentConfig> allAgents();
}
```

Typed record — domain code never sees `Map<String, Object>`. Future adapters (e.g.
the deployment module adapter) handle conversion from `ProviderConfig`'s raw map.

### 2.2 Consumer changes

All consumers of `OpenClawCasehubConfig.agents()` migrate to `AgentProviderConfigSource`:

**`OpenClawWorkerProvisioner`** and **`ReactiveOpenClawWorkerProvisioner`** — replace
`OpenClawCasehubConfig config` with `AgentProviderConfigSource configSource`:

- `resolveAgentId(capabilities)` — iterates `configSource.allAgents().entrySet()`,
  filters by capability superset match. Same algorithm, different source.
- `getCapabilities()` — flatMaps from `configSource.allAgents().values()`.
- `provision()` — reads `configSource.allAgents().get(agentId).sessionKey()`.

`ReactiveOpenClawWorkerProvisioner` (`@IfBuildProperty(name = "casehub.qhorus.reactive.enabled",
stringValue = "true")`) has identical config usage — the swap is mechanical.

**`ExampleController`** (`app/src/main/java/.../example/ExampleController.java`) — reads
`config.agents().get(agentId).sessionKey()` at line 126. Migrate to inject
`AgentProviderConfigSource` and read `configSource.allAgents().get(agentId).sessionKey()`.
Same API surface — mechanical change. Without this, the example controller would read
stale config-file data when the deployment adapter ships.

### 2.3 Implementation in `app/`

**`ConfigFileAgentProviderConfigSource`** — `@DefaultBean @ApplicationScoped`:
- Reads from `OpenClawCasehubConfig.agents()` (existing SmallRye config mapping)
- Converts `Map<String, AgentEntry>` → `Map<String, AgentConfig>`
- This is the only implementation shipped in this spec

### 2.4 Dependencies

No new Maven dependencies. The SPI is local to `casehub/`. The `@DefaultBean`
implementation reads from existing `OpenClawCasehubConfig`.

### 2.5 Deferred: deployment module adapter

`DeploymentProviderConfigStore` in `casehub-ops-deployment` has `forAgent(agentId)` but
no enumeration method. `allAgents()` requires iterating all configured agents. A follow-up
issue on `casehub-ops` is needed to add `Set<String> agentIds()` (expose `configs.keySet()`).

When that ships, a `DeploymentAgentProviderConfigSource` can be added in `app/` as a
plain `@ApplicationScoped` bean (wins over `@DefaultBean`). The right activation
mechanism is `@IfBuildProperty` (already used by `ReactiveOpenClawWorkerProvisioner`
in this project) gated on a config flag like `casehub.openclaw.config-source=deployment`.

This future adapter adds `casehub-ops-api` and `casehub-ops-deployment` as dependencies
to `app/` — deferred to its own issue.

### 2.6 Cross-repo issue to file

- `casehub-ops` — add `agentIds()` to `DeploymentProviderConfigStore`. Future direction:
  extract a `ProviderConfigSource` SPI to `casehub-ops-api` when the consumption pattern
  is proven across multiple repos (openclaw, claudony).

---

## §3 MCP Endpoint Auth (#43)

### 3.1 Path-based HTTP security policy

```properties
quarkus.http.auth.permission.mcp.paths=/mcp,/mcp/*
quarkus.http.auth.permission.mcp.policy=authenticated
```

`quarkus-mcp-server-http` uses Vert.x routes, not JAX-RS. `@RolesAllowed` on `@Tool`
methods IS supported — it returns MCP error code `-32001` on auth failure, not HTTP
status codes. This makes it unsuitable for transport-level protection where clients
expect standard HTTP 401/403. Quarkus HTTP security policies apply at the Vert.x routing
layer and return proper HTTP status codes, making them the correct enforcement point for
bearer token validation.

Both layers complement each other: HTTP auth policy gates transport access; `@RolesAllowed`
on `@Tool` methods can provide fine-grained tool-level authorization in the future.

### 3.2 Dev and test profiles

- The policy from §3.1 is added to main `application.properties`.
- Dev: `%dev.quarkus.security.auth.enabled-in-dev-mode=false` already disables all
  security enforcement. No change needed.
- Test: inherits the policy from main `application.properties`. `@TestSecurity`
  annotations set the security context in `@QuarkusTest`.

### 3.3 Security tests

Add to `OpenClawRestSecurityTest`:

- `unauthenticated_mcp_returns401()` — POST to `/mcp` without credentials → 401
- `authenticated_mcp_isNotForbidden()` — POST to `/mcp` with `@TestSecurity` → not 401/403

### 3.4 Out of scope

- **Role-based MCP tool access:** future concern. `authenticated` is sufficient for now.
  `@RolesAllowed` on individual `@Tool` methods is the correct mechanism when needed.
- **MCP client credential provisioning:** Claude CLI and OpenClaw plugin need to be
  configured to send OIDC bearer tokens. Deployment config, documented in integration spec.
- **Webhook signing:** openclaw#44, separate issue.
- **CORS:** `quarkus-mcp-server-http` has built-in DNS rebinding protection (origin check
  for localhost in `HttpMcpServerRecorder`). Production CORS policy beyond localhost is
  deployment-specific configuration, not application code.

---

## Protocol Coherence

| Protocol | Location | Status |
|----------|----------|--------|
| `auth-retrofit-readiness` | garden | Compliant — #43 uses path-based HTTP policy, no auth in domain |
| `delivery-webhook-cross-tenant-reads` | project | Compliant — DirectCallDeliveryResource keeps @PermitAll, no entity reads |
| `mcp-tool-no-instance-cache` | project | N/A — DirectCallBridge is a rendezvous, not an entity cache |
| `bridge-module-spi-placement` | garden | Compliant — AgentProviderConfigSource SPI in casehub/ (local types) |
| `oidc-cdi-qhorus-exclusion` | project | Compliant — no new CurrentPrincipal bean |
| `alternative-extension-patterns` | garden | Compliant — @DefaultBean fallback with @IfBuildProperty-gated override (no ambiguity risk) |

## Cross-repo issue to file

- `casehub-ops` — add `agentIds()` to `DeploymentProviderConfigStore`; note
  `ProviderConfigSource` SPI extraction as future direction in issue body
