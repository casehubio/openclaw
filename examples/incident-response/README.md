# Incident Response Example

**Built on:** ["How I Run 8 AI Employees 24/7 with OpenClaw"](https://www.youtube.com/watch?v=CYBZmwOmsk8)

The tutorial shows overnight ops agents monitoring and acting on events. This
example runs the same pattern for a production incident — Investigator + Resolver
— with one rule: nothing touches production without human approval.

## What CaseHub adds

- Investigator uses `casehub_checkpoint` to report findings mid-investigation
- Resolver cannot apply the config change without the oversight gate
- If the Investigator can't determine root cause, it escalates via `casehub_escalate`
  (commitment transitions to DELEGATED — tracked in the ledger)
- Second run: ChannelContextWindow injects prior channel history into the Investigator

## The wow moment

The alert fired at 02:47 UTC. The Investigator has traced root cause to a deploy
at 02:31 that reduced the DB pool from 20 to 5. The Resolver has the fix ready.
It's waiting. Run `./approve.sh` to apply the change. Error rate returns to 0.3%.

## Running

```bash
cp .env.example .env
# Edit .env — add your ANTHROPIC_API_KEY
docker compose up -d
./scenario.sh
# When the gate fires:
./approve.sh          # apply the fix
./approve.sh reject   # decline and stop

# Run again to see ChannelContextWindow inject prior session history
./scenario.sh
```

## Mock services

| Service | Port | What it simulates |
|---------|------|-------------------|
| mock-logs | 5001 | Service logs + deploy history |
| mock-config | 5002 | Service config API (GET + PATCH) |

## Agents

| Agent | Gates? | Key tools |
|-------|--------|-----------|
| Investigator | No (agentId="investigator" ≠ "resolver") | `casehub_checkpoint`, `casehub_done`, `casehub_escalate` |
| Resolver | **Yes** (agentId="resolver" == "resolver") | `casehub_done` |
