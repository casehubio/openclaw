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
  - casehub_block
  - casehub_delegate
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
- `casehub_block(agentId, commitmentId, reason, blockedUntil)` — extend Watchdog deadline while blocked on an external dependency; call casehub_checkpoint("UNBLOCKED: ...") when resolved
- `casehub_delegate(agentId, commitmentId, reason, toAgent)` — transfer a commitment to a named agent or person (use for intentional delegation, not authority escalation)

**When to call these explicitly:**

Call `casehub_commit` when you receive a COMMAND and are personally taking responsibility
for it — not for read-only queries or tasks already tracked by `casehub_create_workitem`.
Call `casehub_done` when the task is genuinely complete. Call `casehub_reject` if you
cannot proceed. Call `casehub_checkpoint` for long-running tasks to prevent false escalation.
Call `casehub_escalate` when a task exceeds your authority or capability.
Call `casehub_block` when you cannot proceed due to an external dependency — extend the deadline rather than letting the Watchdog fire prematurely.
Call `casehub_delegate` when intentionally transferring responsibility to a specific named party.

**Open commitments** from prior sessions are injected at session start. Address them
before starting new work — call `casehub_status` for details.

## Case step responses

**When CaseHub invokes you as a case step (you received a COMMAND and are replying via
the deliver:webhook path), you MUST prefix every response with the speech act type.**
Omitting a prefix is treated as an in-progress update — CaseHub will leave the commitment
open and the Watchdog will escalate, even if you intended to signal completion.

Do not wrap responses in markdown code fences.

**JSON format (preferred — machine-readable, bare JSON only):**

{"type": "DONE", "content": "Your response here."}

**Bracket prefix format (simpler alternative):**

[DONE] Your response here.
[STATUS]: colon after the bracket is also accepted.

Valid types:
- DONE — task complete; commitment resolved as fulfilled
- STATUS — still in progress; Watchdog stays armed (you can send DONE later)
- DECLINE — you cannot complete the task; commitment resolved as declined
- FAILURE — task failed with an error; commitment resolved as failed
- RESPONSE — only if you received a QUERY obligation (not a COMMAND); if in doubt, use DONE
