# casehub-openclaw

CaseHub × OpenClaw integration — bridges the CaseHub agentic harness with the
OpenClaw personal AI agent platform.

## What it provides

- **WorkerProvisioner SPI** — provisions OpenClaw instances as CaseHub workers via the
  `/hooks/agent` REST API; no heartbeat required for in-case steps
- **ChannelBackend SPI** — bidirectional bridge: Qhorus channels ↔ OpenClaw agents
- **ChannelContextWindow** — short-term TTL-evicting buffer of Qhorus channel activity,
  queryable by OpenClaw agents at turn start for cross-channel context awareness
- **Python SDK** — `before_prompt_build` hook that injects ChannelContextWindow content
  as `appendSystemContext` (compaction-safe system prompt injection)
- **MessageObserver SPI** — passively populates ChannelContextWindow from all Qhorus
  channel dispatches

## Architecture

```
core/       — OpenClaw hook API client, ChannelContextWindow ring buffer + REST service
casehub/    — CaseHub SPI implementations
app/        — Quarkus deployment
python/     — Python SDK (own pyproject.toml, published to PyPI independently)
```

## Documentation

- [Integration model](docs/specs/openclaw-integration.md)
- [Skill pack](docs/specs/openclaw-skill-pack.md)
- [Platform context](https://github.com/casehubio/parent/blob/main/docs/repos/casehub-openclaw.md)
- [Research spec](https://github.com/casehubio/parent/blob/main/docs/specs/2026-05-25-openclaw-casehub-integration.md)

## Status

Scaffold only — no implementation yet. See [Epic 1](https://github.com/casehubio/openclaw/issues/1).
