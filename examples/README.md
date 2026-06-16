# casehub-openclaw Examples

Three runnable demos built on popular OpenClaw tutorial patterns — each showing
what CaseHub adds to an existing workflow.

| Example | Community | Reference tutorial |
|---------|-----------|-------------------|
| [`multi-agent-dev-team/`](multi-agent-dev-team/) | GitHub/dev | ["My Multi-Agent Dev Team using OpenClaw"](https://www.youtube.com/watch?v=y-GjRMHfTaU) |
| [`trading-oversight/`](trading-oversight/) | Finance/trading | [OpenClaw for Trading: Complete 2026 Guide](https://openclawforge.com/blog/openclaw-for-trading-complete-2026-guide-automated-trading-ai-agents/) |
| [`incident-response/`](incident-response/) | SRE/ops | ["How I Run 8 AI Employees 24/7 with OpenClaw"](https://www.youtube.com/watch?v=CYBZmwOmsk8) |

## Capability matrix

| Capability | Dev Team | Trading | Incident |
|---|:---:|:---:|:---:|
| Channel-backed commitment lifecycle | ✅ | ✅ | ✅ |
| Single oversight gate | ✅ reviewer | ✅ execution | ✅ resolver |
| Multi-agent handoff | ✅ | ✅ | ✅ |
| `casehub_checkpoint` (Watchdog reset) | ✅ | | ✅ |
| `casehub_create_workitem` (capability demo) | ✅ | | |
| Reject path | | ✅ | ✅ |
| `casehub_escalate` | | | ✅ |
| ChannelContextWindow (second run) | | | ✅ |
| Audit trail | ✅ | ✅ | ✅ |

## Prerequisites

- Docker + Docker Compose
- An Anthropic API key (`ANTHROPIC_API_KEY`)
- The casehub-openclaw Quarkus app built: `JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn install -pl app -am`

## Quick start

1. `cd` into any example directory
2. Copy `.env.example` → `.env` and add your API key
3. `docker compose up -d`
4. `./scenario.sh` — starts the demo (blocks until complete or gate fires)
5. When the gate fires: `./approve.sh` (or `./approve.sh reject`)
