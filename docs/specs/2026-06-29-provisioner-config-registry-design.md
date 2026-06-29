# Migrate AgentProviderConfigSource to ProvisionerConfigRegistry

**Issue:** openclaw#56
**Date:** 2026-06-29
**Status:** Approved

## Problem

`AgentProviderConfigSource` with `allAgents() → Map<String, AgentConfig>` is a parallel SPI to
Claudony's `ProviderConfigSource`. Both provide per-agent config for their respective provisioners.
This is the N×M coupling that `ProvisionerConfigRegistry` (engine#584) eliminates.

## Approach: Typed Resolver

The platform SPI (`ProvisionerConfigRegistry`) returns `Map<String, Object>` — necessarily untyped
because engine-api cannot depend on every provider's config types. The provisioner code needs typed
`AgentConfig(sessionKey, capabilities)` and should not regress to string-key casting.

**Solution:** contain the untyped boundary in a single concrete bean — `OpenClawAgentConfigResolver`.
The resolver wraps the registry and exposes typed methods. Provisioners inject the resolver, never
the registry directly. The `Map<String, Object>` never leaks past the resolver.

```
ProvisionerConfigRegistry (Map<String, Object>)    ← untyped boundary
  → OpenClawAgentConfigResolver (typed AgentConfig) ← conversion happens here
    → OpenClawWorkerProvisioner (typed)
    → ReactiveOpenClawWorkerProvisioner (typed)
    → ExampleController (typed)
```

## OpenClawAgentConfigResolver

Concrete `@ApplicationScoped` bean in the `casehub/` module. Replaces `AgentProviderConfigSource`.

```java
@ApplicationScoped
public class OpenClawAgentConfigResolver {

    private static final Logger LOG = Logger.getLogger(OpenClawAgentConfigResolver.class);
    private static final String PROVIDER = "openclaw";
    static final String KEY_SESSION_KEY = "sessionKey";
    static final String KEY_CAPABILITIES = "capabilities";

    private final ProvisionerConfigRegistry registry;
    private final OpenClawCasehubConfig localConfig;

    @Inject
    public OpenClawAgentConfigResolver(
            ProvisionerConfigRegistry registry,
            OpenClawCasehubConfig localConfig) {
        this.registry = registry;
        this.localConfig = localConfig;
    }

    public record AgentConfig(String sessionKey, List<String> capabilities) {}

    void onStartup(@Observes StartupEvent event) {
        Set<String> agentIds = registry.declaredAgentIds(PROVIDER);
        if (agentIds.isEmpty()) {
            return;
        }
        for (String agentId : agentIds) {
            fromRaw(agentId, registry.configFor(PROVIDER, agentId));
        }
        LOG.infof("Validated %d registry agent configs for provider '%s'",
                agentIds.size(), PROVIDER);
    }

    public Map<String, AgentConfig> allAgents() {
        var result = new LinkedHashMap<String, AgentConfig>();
        for (var entry : localConfig.agents().entrySet()) {
            result.put(entry.getKey(), fromLocalEntry(entry.getValue()));
        }
        for (String agentId : registry.declaredAgentIds(PROVIDER)) {
            result.put(agentId, fromRegistry(agentId));
        }
        return Map.copyOf(result);
    }

    public AgentConfig configFor(String agentId) {
        Map<String, Object> raw = registry.configFor(PROVIDER, agentId);
        if (!raw.isEmpty()) {
            return fromRaw(agentId, raw);
        }
        OpenClawCasehubConfig.AgentEntry entry = localConfig.agents().get(agentId);
        if (entry == null) {
            throw new IllegalArgumentException("No config for agent: " + agentId);
        }
        return fromLocalEntry(entry);
    }

    public Set<String> agentIds() {
        var all = new HashSet<>(registry.declaredAgentIds(PROVIDER));
        all.addAll(localConfig.agents().keySet());
        return Set.copyOf(all);
    }

    private AgentConfig fromRegistry(String agentId) {
        return fromRaw(agentId, registry.configFor(PROVIDER, agentId));
    }

    private AgentConfig fromRaw(String agentId, Map<String, Object> raw) {
        Object sessionKeyObj = raw.get(KEY_SESSION_KEY);
        if (sessionKeyObj == null) {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "': registry config missing required key '"
                + KEY_SESSION_KEY + "'; present keys: " + raw.keySet());
        }
        String sessionKey = String.valueOf(sessionKeyObj);

        Object capsObj = raw.get(KEY_CAPABILITIES);
        List<String> capabilities;
        if (capsObj instanceof List<?> list) {
            capabilities = list.stream().map(String::valueOf).toList();
        } else if (capsObj instanceof String s) {
            capabilities = List.of(s);
        } else if (capsObj == null) {
            capabilities = List.of();
        } else {
            throw new IllegalArgumentException(
                "Agent '" + agentId + "': registry config '" + KEY_CAPABILITIES
                + "' must be a List or String, got: "
                + capsObj.getClass().getName());
        }

        return new AgentConfig(sessionKey, capabilities);
    }

    private static AgentConfig fromLocalEntry(OpenClawCasehubConfig.AgentEntry entry) {
        return new AgentConfig(entry.sessionKey(), entry.capabilities());
    }
}
```

**Key decisions:**

- `AgentConfig` record moves here from `AgentProviderConfigSource` — same shape, new home.
- **Union semantics** — `agentIds()` and `allAgents()` return the union of registry and local
  config agents. Registry takes priority per-agent (overrides local config for the same agentId).
  This matches Claudony's `CompositeProviderConfigSource.declaredAgentIds()` pattern — same
  platform, same SPI, same semantics.
- **Registry-first per-agent resolution** — `configFor()` checks the registry first, falls back
  to local config, throws `IllegalArgumentException` if unknown to both. Per-agent fallback is
  consistent with `agentIds()` union semantics.
- **Convention key constants** — `KEY_SESSION_KEY` and `KEY_CAPABILITIES` are package-private
  constants. These are provider-private keys per garden protocol `PP-20260612-042941`
  (spi-property-keys-cross-module-only): only cross-module keys belong in engine-api, and no
  other provider reads openclaw's keys. The producer (ops's `DeploymentProviderConfigStore`)
  populates these keys from `casehub-deployment.yaml` under the `"openclaw"` provider namespace.
- **Startup validation** — `onStartup(@Observes StartupEvent)` calls `fromRaw()` for every
  declared registry agent on pod startup. Malformed entries fail the pod immediately — the same
  guarantee that `@ConfigMapping` provides for local config. When `NoOpProvisionerConfigRegistry`
  is active (development, tests), `declaredAgentIds()` returns empty and the validation is a
  no-op. At request time, `allAgents()` remains fail-fast as defense in depth.
- `fromRaw()` is the typed boundary. It validates required keys, coerces types defensively
  (`String.valueOf()` for sessionKey, single-String-to-List for capabilities), and throws with
  descriptive messages (including the agent ID) on missing or invalid data. Unknown keys in the
  map are ignored — forward compatibility with newer registry versions.
- **Unknown agent → exception, not empty config.** Unlike Claudony's
  `ClaudonyProviderConfig.EMPTY` (meaningful because Claudony agents have per-field defaults),
  OpenClaw's `AgentConfig` requires a valid `sessionKey`. An empty config would fail downstream —
  throwing at the boundary is clearer.

**Extension point consolidation:** `AgentProviderConfigSource` was an injectable SPI — any
`@Alternative` could displace the `@DefaultBean` implementation. `OpenClawAgentConfigResolver` is
a concrete bean with no interface. The openclaw-specific extension point is deliberately removed.
Override semantics move to the platform: `ProvisionerConfigRegistry`'s own CDI layering
(`NoOpProvisionerConfigRegistry @DefaultBean` displaced by ops's `@Alternative @Priority(1)`)
handles all deployment-time configuration. No openclaw-specific config source override has ever
been deployed, and the registry's per-provider namespacing (`PROVIDER = "openclaw"`) provides
sufficient scoping.

## Consumer Migration

| Consumer | Current injection | New injection |
|----------|------------------|---------------|
| `OpenClawWorkerProvisioner` | `AgentProviderConfigSource configSource` | `OpenClawAgentConfigResolver resolver` |
| `ReactiveOpenClawWorkerProvisioner` | `AgentProviderConfigSource configSource` | `OpenClawAgentConfigResolver resolver` |
| `ExampleController` | `AgentProviderConfigSource configSource` | `OpenClawAgentConfigResolver resolver` |

**Call site migration:**

- `configSource.allAgents()` → `resolver.allAgents()` — mechanically identical.
- `configSource.allAgents().get(id)` in provisioners → `resolver.configFor(id)` — behavioural
  upgrade: `resolveAgentId()` already validates the agent exists, so the old `.get(id)` would
  NPE on null rather than return it. `configFor(id)` throws `IllegalArgumentException` with a
  descriptive message instead — strictly better.
- `configSource.allAgents().get(id)` in ExampleController → `resolver.allAgents().get(id)` —
  mechanically identical, preserves existing null-check pattern. Alternatively, use
  `resolver.agentIds().contains(id)` pre-check followed by `resolver.configFor(id)`.

**Behavioural note:** `configFor(id)` throws for unknown agents where `allAgents().get(id)`
returns null. Callers that need null semantics continue using `allAgents().get(id)`. Callers
that want fail-fast use `configFor(id)`.

## Deletions

| File | Reason |
|------|--------|
| `AgentProviderConfigSource` (casehub/) | Replaced by resolver |
| `ConfigFileAgentProviderConfigSource` (app/) | Fallback logic folded into resolver |
| `ConfigFileAgentProviderConfigSourceTest` (app/) | Replaced by resolver test |

## Testing

**`OpenClawAgentConfigResolverTest`** (unit test, casehub/):

- Registry populated → resolver returns typed `AgentConfig` from `Map<String, Object>`
- Registry empty (NoOp) → resolver falls back to `OpenClawCasehubConfig`
- Registry + local config → `agentIds()` returns union; `allAgents()` merges with registry priority
- Agent in local config only → discoverable via `agentIds()` and `allAgents()`
- Agent in registry only → discoverable via `agentIds()` and `allAgents()`
- Registry displaces local config → same agentId in both sources → registry wins
- Unknown agentId → `configFor("nonexistent")` throws `IllegalArgumentException`
- Missing `sessionKey` → `fromRaw()` throws with message naming the missing key
- `capabilities` as single String → coerced to single-element list
- `capabilities` absent → defaults to empty list
- `capabilities` wrong type (e.g. Integer) → throws with message naming expected and actual types
- `sessionKey` as non-String (Integer, Boolean) → coerced via `String.valueOf()`
- Error messages include agent ID → "Agent 'finance-agent': registry config missing..."
- Startup validation — malformed registry agent → pod fails to start with descriptive error
- Startup validation — NoOp registry (empty) → no validation, no error
- Startup validation — all agents valid → logs success message with count

**Existing provisioner tests** update mock setup from `AgentProviderConfigSource` to
`OpenClawAgentConfigResolver`. Same test logic, different injection type.

## Module Placement

`OpenClawAgentConfigResolver` → `casehub/` module. Needs `ProvisionerConfigRegistry` from
engine-api (already a casehub/ dependency) and `OpenClawCasehubConfig` (also in casehub/).
No new Maven dependencies.
