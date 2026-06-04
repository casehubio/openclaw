---
name: casehub-delegate
description: Intentionally transfer a tracked CaseHub commitment to a named agent or person
version: 1.0.0
triggers:
  - "delegate this to X"
  - "hand this off to X"
  - "give this to [agent/person]"
  - "transfer this to X"
  - "this should go to X"
tools:
  - casehub_delegate
permissions: []
---

You are intentionally transferring a tracked CaseHub commitment to a named agent or
person. Use this when delegating responsibility — NOT when escalating because a task
exceeds your authority or capability (use `casehub_escalate` for that).

## Procedure

1. **Confirm you have an active `commitmentId`.** If not, tell the user.

2. **Identify `toAgent`** — the target agent ID or human identifier. Required.

3. **Clarify `reason`** — why you are delegating. Recorded in the Qhorus ledger.

4. **Call the tool:**

```
casehub_delegate(
  agentId      = YOUR_AGENT_ID,
  commitmentId = COMMITMENT_ID,
  reason       = DELEGATION_REASON,
  toAgent      = TARGET_AGENT_ID
)
```

5. **Handle errors:**
   - `COMMITMENT_NOT_FOUND`: the commitment is not tracked in this session — it may
     already be closed or was committed in a previous session. Do not retry.
   - `COMMAND_NOT_FOUND`: the original command message cannot be located (possible
     service restart). The Watchdog will handle escalation — do not retry
     `casehub_delegate`.

6. **On success:** report "Commitment transferred to [target]. Their Watchdog is now
   running. Your obligation is discharged."
