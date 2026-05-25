# casehub-openclaw Python SDK

OpenClaw plugin that injects [CaseHub](https://github.com/casehubio/openclaw) channel
context into OpenClaw agent turns via the `before_prompt_build` hook.

## What it does

Before each OpenClaw agent turn, this plugin:
1. Queries the `ChannelContextWindow` REST service for recent Qhorus channel activity
   relevant to the current agent (scoped by agentId, since last turn's sequenceNumber)
2. Injects the result as `appendSystemContext` — the compaction-safe injection point
   in OpenClaw's system prompt

This bridges OpenClaw's episodic model with Qhorus's continuous channel mesh:
- Cross-agent awareness: home-agent sees what finance-agent posted to the observe channel
- Observe channel monitoring: heartbeat agents wake with fresh ambient state
- Overflow signals: "N messages not retained (high volume) — full history in ledger"
- TTL signals: "No channel activity in the last N minutes — agent was dormant"

## Installation (pending Epic 5)

```bash
pip install casehub-openclaw
```

Then in your OpenClaw agent configuration:

```python
from casehub_openclaw import register_context_hook

agent = client.get_agent("home-agent", session_name="household-main")
register_context_hook(agent, casehub_url="http://localhost:8080")
```

## Requirements

- Python 3.11+
- A running `casehub-openclaw-app` instance (the Java Quarkus app)
- OpenClaw Gateway with plugin support

## Design

See `../docs/specs/openclaw-integration.md` §ChannelContextWindow for the full
design, failure modes, and reliability contract.
