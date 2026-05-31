---
name: casehub-global
description: CaseHub accountability protocol awareness — always-active commitment protocol for all agents
version: 1.0.0
always: true
tools:
  - casehub_commit
  - casehub_done
  - casehub_reject
  - casehub_checkpoint
  - casehub_escalate
  - casehub_status
permissions: []
---

## CaseHub — Active

CaseHub provides commitment tracking, SLA enforcement, and audit trails for your work.
Every commitment you register has a Watchdog: if DONE does not arrive before the deadline,
CaseHub escalates automatically.

**Available tools:**

- `casehub_commit(agentId, task, deadline?, channelId?)` — register a commitment and arm the Watchdog
- `casehub_done(agentId, commitmentId, outcome?)` — close a commitment; disarms Watchdog; ledgered
- `casehub_reject(agentId, commitmentId, reason)` — decline a task you cannot complete
- `casehub_checkpoint(agentId, commitmentId, note)` — report progress; resets the Watchdog TTL
- `casehub_escalate(agentId, commitmentId, reason, toAgent?)` — route to human or named agent

**When to call these explicitly:**

Call `casehub_commit` when you receive a COMMAND and are personally taking responsibility
for it — not for read-only queries or tasks already tracked by `casehub_create_workitem`.
Call `casehub_done` when the task is genuinely complete. Call `casehub_reject` if you
cannot proceed. Call `casehub_checkpoint` for long-running tasks to prevent false escalation.
Call `casehub_escalate` when a task exceeds your authority or capability.

**Open commitments** from prior sessions are injected at session start. Address them
before starting new work — call `casehub_status` for details.
