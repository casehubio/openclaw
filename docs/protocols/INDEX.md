# Protocols — casehub-openclaw

Navigation hub. Each section links to a tier sub-index for the full listing.

## casehub/ — CaseHub Integration Rules

Project-specific rules for the openclaw ↔ CaseHub integration layer.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [casehub/mcp-tool-no-instance-cache.md](casehub/mcp-tool-no-instance-cache.md) | No in-memory caches for entity associations in @ApplicationScoped MCP beans | app/mcp/ |
| [casehub/gate-fail-open-asymmetry.md](casehub/gate-fail-open-asymmetry.md) | Classifier exception → GateRequired fail-safe; infrastructure failure → Autonomous | OversightGateService.openGate() |
| [casehub/gate-context-sentinel-guard.md](casehub/gate-context-sentinel-guard.md) | Guard commandMessageId < 0 before constructing GateContext | openGate() sentinel guard |

See [casehub/INDEX.md](casehub/INDEX.md) for the full casehub tier listing.
