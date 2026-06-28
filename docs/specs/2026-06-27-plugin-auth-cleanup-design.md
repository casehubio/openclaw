# Plugin Auth, CurrentPrincipal Cleanup, Qhorus MCP Entry

**Branch:** `issue-42-plugin-tenant-and-cleanup`
**Covers:** #42, #47, #48
**Date:** 2026-06-27 (revised 2026-06-28)

---

## #48 — Remove CurrentPrincipal exclude-types entries + stale comments

### Background

Platform #111 shipped `OidcCurrentPrincipal @Alternative @Priority(100)`, which automatically
displaces all non-alternative `CurrentPrincipal` implementations via CDI priority. Platform #112
bumped `FixedCurrentPrincipal` to `@Alternative @Priority(200)` so test fixtures still win.

CDI resolution (verified from bytecode in installed JARs):

| Implementation | Annotations | Resolution |
|---|---|---|
| `OidcCurrentPrincipal` | `@RequestScoped @Alternative @Priority(100)` | Production winner |
| `MockCurrentPrincipal` | `@ApplicationScoped @DefaultBean` | Yields to any alternative |
| `FixedCurrentPrincipal` | `@ApplicationScoped @Alternative @Priority(200)` | Test winner (200 > 100) |
| `QhorusInboundCurrentPrincipal` | `@ApplicationScoped` (plain bean) | Displaced by activated alternative |

### Changes

**`app/src/main/resources/application.properties`:**

Remove from `quarkus.arc.exclude-types`:
- `io.casehub.platform.mock.MockCurrentPrincipal`
- `io.casehub.qhorus.runtime.identity.QhorusInboundCurrentPrincipal`

Remove the entire comment block for these two entries. OidcCurrentPrincipal now has
`@Alternative @Priority(100)` (platform#111) which displaces both via standard CDI resolution.
The exclusions and their explanatory comments are no longer needed.

Keep the 3 engine no-op exclusions — our blocking implementations replace those.

**`app/src/test/resources/application.properties`:**

Remove: `quarkus.arc.selected-alternatives=io.casehub.platform.testing.FixedCurrentPrincipal`

`FixedCurrentPrincipal @Alternative @Priority(200)` (platform#112 — note: bytecode shows
`@Priority(200)`, not `@Priority(1)`) is globally activated by its annotation.
`selected-alternatives` is redundant — Priority(200) beats Priority(100) automatically.

Update comment to reflect the new state: FixedCurrentPrincipal wins via `@Priority(200)`
over `OidcCurrentPrincipal @Priority(100)` without any `selected-alternatives` config.

---

## #47 — Add second mcpServers entry for Qhorus at /qhorus

### Background

qhorus#306 scoped Qhorus MCP tools to a named server at `/qhorus` via `@McpServer("qhorus")`.
This establishes the platform convention: library MCP tools use `@McpServer` with a named
server; the default server belongs to the application.

Verified: `casehub-qhorus-0.2-SNAPSHOT.jar` embeds `META-INF/microprofile-config.properties`:
```
quarkus.mcp.server.qhorus.http.root-path=/qhorus
quarkus.mcp.server.qhorus.server-info.name=qhorus
quarkus.mcp.server.qhorus.tools.page-size=0
```

Without this embedded config, both the default and named servers would serve at `/mcp`
(`McpHttpServerBuildTimeConfig.rootPath` defaults to `/mcp` for all servers including named ones).

### Changes

**`skills/README.md`:**

Update the `mcpServers` configuration example (§3. Configure OpenClaw) to include the Qhorus
entry alongside the existing CaseHub entry:

```json
{
  "mcp": {
    "servers": {
      "casehub": {
        "transport": "streamable-http",
        "url": "http://localhost:8080/mcp"
      },
      "qhorus": {
        "transport": "streamable-http",
        "url": "http://localhost:8080/qhorus"
      }
    }
  }
}
```

`casehub` serves commitment tools (Layer 0). `qhorus` serves agent mesh tools (channels,
dispatch). Both are served by the same Quarkus process on different root paths.

No code changes — documentation only.

---

## #42 — Plugin endpoint tenant isolation

### Background

`/openclaw/plugin/*` endpoints are `@PermitAll`. The TypeScript plugin runs inside OpenClaw
and calls these endpoints via plain `fetch()` with no auth headers. Any caller on the internal
network who knows the URL can create, close, or read commitments for any agent.

The issue was originally marked blocked by upstream OpenClaw support for service-account tokens.
This blocker is not real — the plugin controls its own HTTP calls (`CommitmentManager.post()`,
`CommitmentManager.get()`) and can read auth credentials from plugin config and add headers.

### Design: bridge mechanism + role-based endpoint auth + app-local CurrentPrincipal

Endpoints are designed for the platform's OIDC future (`@RolesAllowed`). A custom
`HttpAuthenticationMechanism` bridges the gap until the plugin supports OIDC client-credentials.
When that happens, remove the bridge with zero endpoint changes (#52 tracks the migration).

#### Critical: SecurityIdentity × OidcCurrentPrincipal interaction

Creating a non-anonymous SecurityIdentity for plugin requests breaks
`OidcCurrentPrincipal.tenancyId()`. The chain (verified from bytecode):

1. Bridge mechanism authenticates → non-anonymous SecurityIdentity (no JWT)
2. `OidcCurrentPrincipal.tenancyId()` → `identity.isAnonymous()` = false → reads `jwt.claim("tenancyId")`
3. No JWT exists → `MissingTenancyException` → 500

Impact: `POST /openclaw/plugin/done` and `GET /openclaw/plugin/commitments/{agentId}` both
call `CommitmentService` → `JpaCommitmentStore` query methods → `currentPrincipal.tenancyId()`.
Only `POST /openclaw/plugin/commit` survives (calls `store.save()` which doesn't query tenancyId).

Tests don't catch this: `FixedCurrentPrincipal @Priority(200)` wins in tests; `OidcCurrentPrincipal`
never exercises its JWT path. The regression manifests only in production.

**Cross-tenant store alternative evaluated and rejected:** `CrossTenantCommitmentStore` exists
(used by `OpenClawDeliveryResource` via `@Inject @CrossTenant`), but its API surface lacks
`findByCorrelationId()` — the `done()` endpoint can't use it without a qhorus API extension.

**Fix:** App-local `OpenClawCurrentPrincipal @Alternative @Priority(150)`. See §New classes below.

#### New: `PluginTokenBridgeMechanism`

Location: `app/src/main/java/io/casehub/openclaw/app/security/PluginTokenBridgeMechanism.java`

Implements `HttpAuthenticationMechanism`. Path guard in `authenticate()` returns null for
non-plugin paths; handles `/openclaw/plugin/*` exclusively.

**Implementation note (deviation from original design):** `auth-mechanism=plugin-token` config
binding was designed but dropped during implementation. In Quarkus 3.32.2, `auth-mechanism`
overrides `@TestSecurity` routing — the framework routes to the named mechanism before the
test mechanism can intercept, breaking all functional tests with 500. Additionally, declaring
`getCredentialTransport()` with `Type.AUTHORIZATION, "bearer"` conflicts with OIDC's Bearer
transport registration. The implemented isolation strategy uses a path guard in `authenticate()`
and `getCredentialTransport()` returning null ("this mechanism cannot interfere with other
mechanisms" — Javadoc).

```
authenticate():
  path not /openclaw/plugin/* → null (defer to OIDC)
  path is /openclaw/plugin/*:
    token configured + matches → SecurityIdentity(
        principal="openclaw-plugin",
        role="openclaw-plugin",
        attribute "casehub.plugin.bridge"=true)
    token configured + mismatch/missing → AuthenticationFailedException (401)

getCredentialTransport():
  null (avoids Bearer transport conflict with OIDC)

getChallenge():
  ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"openclaw-plugin\"")
```

Config property: `casehub.openclaw.plugin.bearer-token` (required — see Configuration below).

Coexistence with OIDC: path guard returns null for non-plugin paths, allowing OIDC to handle
`/mcp` and other authenticated endpoints without interference.

The SecurityIdentity attribute `casehub.plugin.bridge=true` is the detection marker for
`OpenClawCurrentPrincipal` (see below). It's not coupled to the `openclaw-plugin` role —
an OIDC token that grants the same role would not have this attribute.

#### New: `OpenClawCurrentPrincipal`

Location: `app/src/main/java/io/casehub/openclaw/app/security/OpenClawCurrentPrincipal.java`

The app's composite `CurrentPrincipal`. Handles two identity contexts:

```
@RequestScoped
@Alternative
@Priority(150)  // OidcCurrentPrincipal(100) < this < FixedCurrentPrincipal(200)
public class OpenClawCurrentPrincipal implements CurrentPrincipal {

    @Inject SecurityIdentity identity;
    @Inject OidcCurrentPrincipal oidcPrincipal;  // injected by concrete type — no alternative competition

    private static final String DEFAULT_TENANCY = "278776f9-e1b0-46fb-9032-8bddebdcf9ce";
    // Hardcoded — matches OidcCurrentPrincipal, FixedCurrentPrincipal, Commitment entity.
    // Four platform classes independently hardcode this value. A shared platform constant
    // would be better (platform-level concern, not this spec's scope).

    Bridge identity (casehub.plugin.bridge attribute present):
      actorId()            → identity.getPrincipal().getName()  ("openclaw-plugin")
      groups()             → identity.getRoles()
      tenancyId()          → DEFAULT_TENANCY ("278776f9-e1b0-46fb-9032-8bddebdcf9ce")
      isCrossTenantAdmin() → false

    All other paths (OIDC, anonymous):
      delegates to oidcPrincipal.*()
}
```

Priority chain: `FixedCurrentPrincipal(200)` > `OpenClawCurrentPrincipal(150)` >
`OidcCurrentPrincipal(100)` > `MockCurrentPrincipal(@DefaultBean)`.

Path verification:
- **Plugin path:** mechanism stamps `casehub.plugin.bridge=true` → OpenClawCurrentPrincipal
  returns default tenancyId → all store queries work with default tenant ✓
- **MCP/OIDC path:** no bridge attribute → delegates to OidcCurrentPrincipal → reads JWT ✓
- **Delivery (@PermitAll):** anonymous identity, no bridge attribute → delegates to
  OidcCurrentPrincipal → `isAnonymous()=true` → default UUID ✓
- **Tests:** FixedCurrentPrincipal(200) wins → test path unchanged ✓

Long-term: platform#121 proposes `OidcCurrentPrincipal` check SecurityIdentity attributes
before falling through to JWT. When shipped, `OpenClawCurrentPrincipal` can be removed.

#### Modified: `PluginCommitResource`

Replace `@PermitAll` with `@RolesAllowed(OpenClawGroups.PLUGIN)`. No business logic changes.

#### Modified: `OpenClawGroups`

Add: `public static final String PLUGIN = "openclaw-plugin";`

#### Modified: TypeScript plugin

`index.ts` — read `pluginToken` from `api.config.casehub.pluginToken`, pass to
`CommitmentManager` constructor.

`CommitmentManager` — accept optional token in constructor. `post()` and `get()` add
`Authorization: Bearer <token>` header when token is present.

`ChannelClient` — NOT changed. `/channel-context/*` protection deferred to #51.

#### Configuration

**`app/src/main/resources/application.properties`:**
```properties
# Plugin endpoint auth — bridge mechanism validates pre-shared bearer token (openclaw#42).
# Migrate to OIDC client-credentials when available (openclaw#52).
casehub.openclaw.plugin.bearer-token=${OPENCLAW_PLUGIN_TOKEN}

# Plugin endpoints: require auth (openclaw#42)
# auth-mechanism=plugin-token was dropped — see PluginTokenBridgeMechanism implementation note.
quarkus.http.auth.permission.plugin.paths=/openclaw/plugin/*
quarkus.http.auth.permission.plugin.policy=authenticated

# Dev mode: plugin paths don't require auth (mechanism never consulted)
%dev.quarkus.http.auth.permission.plugin.policy=permit
%dev.casehub.openclaw.plugin.bearer-token=dev-unused
```

Required property: production fails at boot if `OPENCLAW_PLUGIN_TOKEN` env var is unset.
`%dev` override makes the value irrelevant (mechanism never consulted due to `policy=permit`).

**`app/src/test/resources/application.properties`:**
```properties
%test.casehub.openclaw.plugin.bearer-token=test-plugin-token
```

**`skills/README.md`:**
Update plugin config example to include `pluginToken`.

#### Test changes

**`PluginCommitResourceTest`:** Add `@TestSecurity(user = "plugin", roles = {OpenClawGroups.PLUGIN})`
at class level. `@TestSecurity` provides mock identity — mechanism not called. Functional tests
unchanged.

**`OpenClawRestSecurityTest`:**
- Rename `permitAll_pluginCommitments_noAuthRequired` → `unauthenticated_plugin_returns401`:
  no `@TestSecurity`, mechanism enforces against configured test token → 401.
- Add `authenticated_plugin_isNotForbidden`: `@TestSecurity(user = "plugin",
  roles = {OpenClawGroups.PLUGIN})` → not 401/403.
- Add end-to-end mechanism tests:
  - `validBearerToken_plugin_passesAuth()` — sends `Authorization: Bearer test-plugin-token` →
    not 401/403
  - `invalidBearerToken_plugin_returns401()` — sends `Authorization: Bearer wrong-token` → 401
  - `pluginToken_doesNotAuthenticateMcpEndpoint()` — sends plugin token to `/mcp` → 401
    (mechanism isolation — `auth-mechanism` routing prevents cross-mechanism leakage)

**`OpenClawCurrentPrincipalTest` (new unit test):**

Dedicated unit test for `OpenClawCurrentPrincipal` delegation logic. Required because
`InMemoryCommitmentStore @Alternative @Priority(1)` does not inject `CurrentPrincipal` or
filter by tenancyId — the production-only `MissingTenancyException` regression is invisible
to all e2e tests.

Test cases:
- Bridge attribute present → `tenancyId()` returns `"278776f9-e1b0-46fb-9032-8bddebdcf9ce"`,
  `actorId()` returns principal name, `groups()` returns identity roles,
  `isCrossTenantAdmin()` returns false
- Bridge attribute absent, non-anonymous → each method delegates to `OidcCurrentPrincipal`
- Anonymous identity → each method delegates to `OidcCurrentPrincipal` (handles anonymity)

---

## Related issues filed

| # | Description | Why deferred |
|---|---|---|
| #51 | Protect `/channel-context/{agentId}` with plugin auth | Also consumed by Python SDK — broader scope |
| #52 | Migrate plugin auth from bridge token to OIDC | Blocked by upstream OpenClaw OIDC support |
| platform#121 | OidcCurrentPrincipal handle non-OIDC SecurityIdentity types | Platform change — enables removal of app-local OpenClawCurrentPrincipal |

---

## Platform coherence review

| Check | Result |
|---|---|
| auth-retrofit-readiness: @RolesAllowed on resources | ✅ `@RolesAllowed(OpenClawGroups.PLUGIN)` |
| auth-retrofit-readiness: no auth in domain/service | ✅ mechanism + CurrentPrincipal are adapter-layer only |
| auth-retrofit-readiness: REST resources stay thin | ✅ no business logic change |
| auth-mechanism policy binding | ✅ consistent with existing `/mcp` auth pattern |
| openclaw-delivery-always-200: delivery endpoints | ✅ not touched — delivery stays @PermitAll |
| CDI: OidcCurrentPrincipal @Alternative displaces plain beans | ✅ verified from bytecode |
| CDI: FixedCurrentPrincipal @Priority(200) wins in tests | ✅ verified from bytecode (200, not 1) |
| CDI: OpenClawCurrentPrincipal @Priority(150) delegates correctly | ✅ all four paths verified |
| Platform convention: library MCP uses @McpServer | ✅ #47 documents the Qhorus entry |
| Cross-tenant pattern: delivery endpoints use @CrossTenant stores | ✅ not applicable here (CrossTenantCommitmentStore lacks findByCorrelationId) |
| RFC 7235: getChallenge() returns WWW-Authenticate | ✅ Bearer realm="openclaw-plugin" |
