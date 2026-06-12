# casehub/ — CaseHub Integration Protocols

Rules specific to the casehub-openclaw integration layer.

| File | Rule Summary | Applies To |
|------|-------------|------------|
| [mcp-tool-no-instance-cache.md](mcp-tool-no-instance-cache.md) | No in-memory caches for entity associations in @ApplicationScoped MCP beans | app/mcp/ — any @Tool bean resolving correlationId → channelId or similar |
| [gate-fail-open-asymmetry.md](gate-fail-open-asymmetry.md) | Classifier exception → GateRequired fail-safe; infrastructure failure → Autonomous | OversightGateService.openGate() and any future gate-opening code |
| [gate-context-sentinel-guard.md](gate-context-sentinel-guard.md) | Guard commandMessageId < 0 before constructing GateContext — never persist a sentinel | openGate() — any code reading a message ID into a persisted record |
| [delivery-webhook-cross-tenant-reads.md](delivery-webhook-cross-tenant-reads.md) | Delivery webhook handlers must use cross-tenant stores for all entity reads | Any @Path('/openclaw/delivery/*') handler reading Qhorus entities |
