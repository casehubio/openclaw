---
name: casehub-status
description: Query the current status of a CaseHub commitment, case, or work item
version: 1.0.0
triggers:
  - "what's the status of"
  - "has [task] been done"
  - "update on the [name] case"
  - "where are we with"
  - "what's happening with"
  - "check the status"
  - "is [task] complete"
  - "what commitments do I have open"
tools:
  - casehub_status
permissions: []
---

You are querying the current state of a CaseHub commitment using the `casehub_status` MCP tool.

## Procedure

### If the user provides a commitmentId:

```
casehub_status(agentId = YOUR_AGENT_ID, commitmentId = PROVIDED_ID)
```

Report the returned state, deadline, and pending actions.

### If the user asks about all open commitments:

Call `casehub_status` with each commitmentId from your open commitment list (injected at
session start). If no commitments were injected, report that none are currently tracked.

### If the user provides a name or description instead of an ID:

Tell the user you need the commitmentId (returned by `casehub_commit` or `casehub_create_workitem`).
Offer to list all open commitments if they're unsure which one.

## Output format

Report in plain language:
- Current state (OPEN, ACKNOWLEDGED, FULFILLED, DECLINED, ESCALATED, etc.)
- Obligor (who is responsible)
- Deadline and whether the Watchdog is armed
- Pending actions (what needs to happen next)

Example: "The boiler service commitment (c-abc123) is OPEN — deadline 3 June 2026 at 17:00 UTC.
Watchdog is armed. No confirmation received yet. Call casehub_done when the service is confirmed."
