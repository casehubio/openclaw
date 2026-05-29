# casehub-openclaw Python SDK

Python client library for the CaseHub ChannelContextWindow REST API. Used by
OpenClaw Python skill scripts to query recent Qhorus channel activity.

> **Note:** Automatic `before_prompt_build` injection is handled by the
> **TypeScript plugin** (`casehub-openclaw-plugin` on npm), not this package.
> See the TypeScript plugin's README for automatic injection setup.

## What this package is for

OpenClaw skill scripts are Python scripts invoked as supporting resources
in SKILL.md files. This package provides a typed HTTP client so Python skill
scripts can explicitly query the ChannelContextWindow before acting.

## Installation

```bash
pip install casehub-openclaw
```

Requires a running `casehub-openclaw` Quarkus app instance.

## Usage

```python
from casehub_openclaw import ChannelClient, WindowContent
from datetime import datetime, timezone

client = ChannelClient("http://localhost:8080")
content: WindowContent = client.get_context("home-agent", since=0)

if not content.agent_has_association:
    # Agent not yet wired to any Qhorus channels
    pass
elif content.messages:
    for msg in content.messages:
        print(f"{msg.sender_id} [{msg.message_type}]: {msg.content}")
```

## Error handling

`get_context` raises `httpx.HTTPStatusError` on non-2xx and
`httpx.TimeoutException` on timeout. Callers decide whether to fail open.

```python
import httpx

try:
    content = client.get_context("home-agent", since=0)
except (httpx.HTTPStatusError, httpx.TimeoutException):
    content = None  # proceed without context
```

## Design

See `../docs/specs/openclaw-integration.md` §ChannelContextWindow for the full
design, failure modes, and reliability contract.
