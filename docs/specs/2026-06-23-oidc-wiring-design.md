# OIDC Wiring Design — openclaw#41

**Branch:** `issue-41-wire-oidc`
**Date:** 2026-06-23
**Status:** Approved (rev 2 — post-review)

---

## 1. Problem

`casehub-platform-oidc` is absent from the classpath. Consequences:

- `CurrentPrincipal.groups()` returns an empty set — `OidcCurrentPrincipal @RequestScoped` is not active; `QhorusInboundCurrentPrincipal @ApplicationScoped` is the CDI winner.
- `@RolesAllowed` annotations have no effect — the RBAC enforcement chain is inert.
- OIDC cannot validate bearer tokens from authenticated callers.

casehub-life is already wired (life#40, 2026-06-22) and is the reference implementation.

---

## 2. Architecture — integration tier constraint

casehub-openclaw is an integration tier, not an application tier. Its REST surface is predominantly system-to-system: OpenClaw webhook callbacks, Python SDK calls, TypeScript plugin calls. **No casehub OIDC token is present at the call site for these callers.** This is a structural reality, not a policy choice.

The first-principles question for each endpoint: *does a casehub OIDC token exist at call time?*

| Resource | Caller | Token present? | Annotation |
|---|---|---|---|
| `OpenClawDeliveryResource` | OpenClaw webhook engine | No | `@PermitAll` |
| `OpenClawOversightDeliveryResource` | OpenClaw webhook engine | No | `@PermitAll` |
| `ChannelContextWindowResource` | Python SDK `before_prompt_build` | No | `@PermitAll` |
| `PluginCommitResource` | TypeScript plugin hooks | No | `@PermitAll` |
| `ExampleController` | Human operator | Yes | `@RolesAllowed(OpenClawGroups.ADMIN)` |
| MCP endpoint (`POST /mcp`) | LLM via Quarkus MCP server | — | Out of scope — not a JAX-RS resource (openclaw#43) |

`@PermitAll` over unannotated: explicit is always better than implicit. With casehub-platform-oidc activating Quarkus security globally, `@PermitAll` documents the deliberate absence of auth for auditors, survives future policy changes (`quarkus.security.deny-unauthenticated`), and is consistent with how Quarkus security annotations are meant to be used.

**Anonymous sentinel consistency:** For `@PermitAll` endpoints, `OidcCurrentPrincipal` returns sentinel values when `identity.isAnonymous()` is true: `actorId()` → `"anonymous"`, `groups()` → empty, `tenancyId()` → `TenancyConstants.DEFAULT_TENANT_ID` = `"278776f9-e1b0-46fb-9032-8bddebdcf9ce"`. This matches the `QhorusInboundCurrentPrincipal` fallback UUID exactly — no behavioural change on anonymous paths.

**Tenancy isolation on delivery endpoints is already correct:** tenancy is derived from the `channelId` via `CrossTenantChannelStore` (protocol PP-20260612-520281), not from the bearer token. OIDC wiring does not change this.

---

## 3. Changes

### 3.1 `app/pom.xml`

Two entries:

**a) `casehub-platform-oidc` compile dependency:**

```xml
<!-- casehub-platform-oidc — activates OidcCurrentPrincipal @RequestScoped.
     Brings quarkus-oidc transitively. CDI wiring: see openclaw#41 spec §4.2. -->
<dependency>
    <groupId>io.casehub.platform</groupId>
    <artifactId>casehub-platform-oidc</artifactId>
</dependency>
```

Scope: `compile`. Same as life#40.

**b) `quarkus-test-security` test dependency:**

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-test-security</artifactId>
    <scope>test</scope>
</dependency>
```

The BOM manages the version; the dependency must be explicitly declared. Not currently present in `app/pom.xml`.

### 3.2 `app/src/main/resources/application.properties`

**a) Add `QhorusInboundCurrentPrincipal` to `exclude-types`:**

```properties
quarkus.arc.exclude-types=\
  io.casehub.engine.internal.worker.NoOpWorkerProvisioner,\
  io.casehub.engine.internal.worker.NoOpCaseChannelProvider,\
  io.casehub.engine.internal.worker.NoOpWorkerStatusListener,\
  io.casehub.platform.mock.MockCurrentPrincipal,\
  io.casehub.qhorus.runtime.identity.QhorusInboundCurrentPrincipal
```

`OidcCurrentPrincipal` is `@RequestScoped` with no `@Alternative` or `@Priority`. `QhorusInboundCurrentPrincipal` is `@ApplicationScoped` with no `@Alternative`. Two unqualified CDI beans for the same type → `AmbiguousResolutionException` at augmentation. Excluding `QhorusInboundCurrentPrincipal` leaves `OidcCurrentPrincipal` as the sole winner. (The stale `QhorusInboundCurrentPrincipal` Javadoc claiming `OidcCurrentPrincipal` has `@Priority(100)` is incorrect — filed as qhorus#301.)

**b) Add OIDC config section:**

```properties
# OIDC — casehub-platform-oidc (openclaw#41)
# Required deployment env vars (do NOT set values here — empty strings bypass ConfigException):
#   QUARKUS_OIDC_AUTH_SERVER_URL — e.g. https://auth.example.com/realms/casehub
#   QUARKUS_OIDC_CLIENT_ID       — e.g. casehub-openclaw
quarkus.oidc.application-type=service

# Dev profile: disable all security enforcement and OIDC token validation.
# quarkus.security.auth.enabled-in-dev-mode=false activates DevModeDisabledAuthorizationController
# (quarkus-security-runtime-spi 3.32.2): isAuthorizationEnabled()=false — @RolesAllowed inert in dev.
# quarkus.oidc.enabled=false prevents OIDC from attempting token validation or discovery.
%dev.quarkus.security.auth.enabled-in-dev-mode=false
%dev.quarkus.oidc.enabled=false
%dev.quarkus.keycloak.devservices.enabled=false
```

### 3.3 `app/src/test/resources/application.properties`

Add OIDC test profile config. The `%dev.` prefix does not reach `@QuarkusTest` (which uses the `%test` profile). Without this, `casehub-platform-oidc` on the compile classpath causes OIDC to attempt discovery at test startup — which fails with no `auth-server-url` set.

```properties
# OIDC test config — GE-20260521-f50602: discovery-disabled requires jwks-path
# (lazy-loaded by quarkus-oidc; never actually fetched when @TestSecurity is used)
quarkus.oidc.auth-server-url=http://localhost:8180/realms/test
quarkus.oidc.discovery-enabled=false
quarkus.oidc.jwks-path=protocol/openid-connect/certs
quarkus.keycloak.devservices.enabled=false
```

### 3.4 `OpenClawGroups` — new constants class

```
app/src/main/java/io/casehub/openclaw/app/OpenClawGroups.java
```

```java
package io.casehub.openclaw.app;

public final class OpenClawGroups {
    public static final String ADMIN = "openclaw-admin";
    private OpenClawGroups() {}
}
```

Single group for this issue. Establishes the naming convention.

### 3.5 Annotation sweep — existing REST resources

All annotations at class level.

- **`OpenClawDeliveryResource`** — add `@PermitAll`
- **`OpenClawOversightDeliveryResource`** — add `@PermitAll`
- **`ChannelContextWindowResource`** — add `@PermitAll`
- **`PluginCommitResource`** — add `@PermitAll` (tenant isolation deferred: openclaw#42)
- **`ExampleController`** — add `@RolesAllowed(OpenClawGroups.ADMIN)`

### 3.6 `ExampleControllerTest` — add `@TestSecurity` to all 6 methods

`ExampleController` gains `@RolesAllowed(OpenClawGroups.ADMIN)`. All 6 existing test methods make unauthenticated calls and will receive 401. Each method (or the class) must be annotated:

```java
@TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
```

Mechanical change. No business logic in the tests changes — only the auth wrapper is added.

---

## 4. Test strategy

### 4.1 New: `OpenClawRestSecurityTest`

```
app/src/test/java/io/casehub/openclaw/app/security/OpenClawRestSecurityTest.java
```

Pattern from `LifeRestSecurityTest`. Uses `@TestSecurity` which constructs a synthetic `SecurityIdentity` — works independently of CDI CurrentPrincipal wiring.

**ExampleController tests (guarded by `@RolesAllowed(ADMIN)`):**

```java
@Test
void unauthenticated_startExample_returns401() {
    given().contentType(ContentType.JSON)
        .when().post("/example/nonexistent/start")
        .then().statusCode(401);
}

@Test
@TestSecurity(user = "operator", roles = {"openclaw-operator"})
void wrongRole_startExample_returns403() {
    given().contentType(ContentType.JSON)
        .when().post("/example/nonexistent/start")
        .then().statusCode(403);
}

@Test
@TestSecurity(user = "admin", roles = {OpenClawGroups.ADMIN})
void admin_startExample_isNotForbidden() {
    // "nonexistent" yields 400 (Unknown example) — guaranteed regardless of
    // %test.casehub.example.enabled=true; auth gate cleared, business logic reached
    given().contentType(ContentType.JSON)
        .when().post("/example/nonexistent/start")
        .then().statusCode(not(in(List.of(401, 403))));
}
```

**`@PermitAll` endpoint tests (no credentials required):**

```java
@Test
void permitAll_channelContextWindow_noAuthRequired() {
    given().when().get("/channel-context/test-agent")
        .then().statusCode(not(in(List.of(401, 403))));
}

@Test
void permitAll_deliveryChannel_noAuthRequired() {
    given().contentType(ContentType.JSON).body("{}")
        .when().post("/openclaw/delivery/channel/" + UUID.randomUUID())
        .then().statusCode(not(in(List.of(401, 403))));
}

@Test
void permitAll_deliveryOversight_noAuthRequired() {
    given().contentType(ContentType.JSON).body("{}")
        .when().post("/openclaw/delivery/oversight/" + UUID.randomUUID())
        .then().statusCode(not(in(List.of(401, 403))));
}

@Test
void permitAll_pluginCommitments_noAuthRequired() {
    given().when().get("/openclaw/plugin/commitments/test-agent")
        .then().statusCode(not(in(List.of(401, 403))));
}
```

### 4.2 CDI wiring in `@QuarkusTest`

With `casehub-platform-oidc` on the compile classpath and `QhorusInboundCurrentPrincipal` excluded via `quarkus.arc.exclude-types`:

- **Production CDI**: `OidcCurrentPrincipal @RequestScoped` is the sole `CurrentPrincipal` bean
- **Test CDI**: `casehub-platform-testing` (already in test scope) provides `FixedCurrentPrincipal @Alternative @Priority(1)` — displaces `OidcCurrentPrincipal`
- **`@TestSecurity` tests**: Quarkus constructs a synthetic `SecurityIdentity`; `OidcCurrentPrincipal` delegates to it correctly — `@TestSecurity` works with the real OIDC principal in place

`MockCurrentPrincipal` remains in `quarkus.arc.exclude-types` — no change.

---

## 5. Garden GEs applied

- **GE-20260622-580d45** — `%dev.quarkus.security.auth.enabled-in-dev-mode=false` technique applied verbatim.
- **GE-20260601-08a351** — `%dev.quarkus.keycloak.devservices.enabled=false` added defensively (quarkus-oidc can trigger Keycloak DevServices). Also added to test profile.
- **GE-20260521-f50602** — Applied: `quarkus.oidc.discovery-enabled=false` + `jwks-path` in test config prevents OIDC discovery failure at test startup.
- **GE-20260609-77a6f9** — Mitigated: `casehub-platform-testing` `@Alternative @Priority(1)` already in test scope; `QhorusInboundCurrentPrincipal` excluded from production CDI removes the ambiguity.

---

## 6. Deferred — filed as issues

| Issue | Description |
|---|---|
| openclaw#42 | Plugin endpoint tenant isolation — service-account token for `/openclaw/plugin/*` |
| openclaw#43 | MCP endpoint auth — wire Quarkus MCP server auth for `POST /mcp` |
| openclaw#44 | Delivery endpoint hardening — validate signed webhook payloads from OpenClaw |
| qhorus#301 | Stale `QhorusInboundCurrentPrincipal` Javadoc — claims `OidcCurrentPrincipal @Priority(100)`, class has no such annotation |

---

## 7. Out of scope

- No changes to `core/` or `casehub/` modules — this is entirely in `app/`.
- No changes to SPI signatures, service layer, or domain logic.
- No new Flyway migrations.
- MCP endpoint auth (openclaw#43) — different auth mechanism, separate issue.
