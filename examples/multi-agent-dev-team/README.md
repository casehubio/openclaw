# Multi-Agent Dev Team Example

**Built on:** ["My Multi-Agent Dev Team using OpenClaw"](https://www.youtube.com/watch?v=y-GjRMHfTaU)

The canonical tutorial shows how to set up a team of OpenClaw agents to handle
coding work overnight. This example runs the same team — Planner, Coder, Reviewer
— with CaseHub underneath it.

## What CaseHub adds

The tutorial agents act autonomously. With CaseHub:
- Every agent commitment is tracked (OPEN → FULFILLED / DECLINED)
- The Reviewer's completion triggers an oversight gate — nothing merges without
  human approval
- Full audit trail: which agent did what, when, with what outcome

## The wow moment

The Reviewer has done its job. Tests pass. The diff is clean. And the agent
**stops** — it cannot merge without you. Run `./approve.sh` to proceed.

## Running

```bash
cp .env.example .env
# Edit .env — add your ANTHROPIC_API_KEY
docker compose up -d
./scenario.sh
# When the gate fires:
./approve.sh          # approve the merge
./approve.sh reject   # reject it
```

## Mock services

| Service | Port | What it simulates |
|---------|------|-------------------|
| mock-github | 5001 | GitHub REST API (issues, file contents, PR diffs, merges) |
| mock-ci | 5002 | CI run endpoint (always passes, 47 tests) |

## Agents

| Agent | Gates? | Key tools |
|-------|--------|-----------|
| Planner | No (agentId="planner" ≠ "reviewer") | `casehub_done`, `casehub_create_workitem` |
| Coder | No (agentId="coder" ≠ "reviewer") | `casehub_checkpoint`, `casehub_done` |
| Reviewer | **Yes** (agentId="reviewer" == "reviewer") | `casehub_done` |
