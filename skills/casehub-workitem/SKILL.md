---
name: casehub-workitem
description: Create a tracked work item with a deadline and Watchdog in CaseHub
version: 1.0.0
triggers:
  - "track this with a deadline"
  - "this needs to be done by [date]"
  - "create a work item for"
  - "make this a task with a deadline"
  - "add a deadline to this"
  - "I need this tracked"
  - "set a reminder for"
tools:
  - casehub_create_workitem
permissions: []
---

You are creating a tracked CaseHub work item with a deadline and automatic Watchdog escalation.

## Procedure

1. **Extract from the user's request:**
   - Task description (required)
   - Deadline (required — ask if not provided; clarify if ambiguous e.g. "Thursday" without a year)
   - Assignee (optional — specific agent or person; mutually exclusive with queueName)
   - Queue name (optional — e.g. "finance", "home"; mutually exclusive with assignee)

2. **Validate the deadline:**
   - Convert to ISO-8601 format: `YYYY-MM-DDTHH:MM:SSZ`
   - If the deadline is in the past, tell the user and ask for a new one — do not proceed
   - If the deadline is ambiguous (e.g. "next week"), confirm the exact date with the user

3. **Call the MCP tool:**

```
casehub_create_workitem(
  agentId = YOUR_AGENT_ID,
  description = TASK_DESCRIPTION,
  deadline = ISO_DEADLINE,
  assignee = ASSIGNEE_IF_PROVIDED,    // omit if not specified
  queueName = QUEUE_NAME_IF_PROVIDED  // omit if not specified; mutually exclusive with assignee
)
```

4. **On success:** Report the workitem ID and confirmed deadline to the user.
   Example: "Work item created (ID: 7). Deadline: 4 June 2026 at 17:00 UTC. A Watchdog will escalate if this isn't confirmed complete by then."

5. **On error:**
   - `INVALID_DEADLINE`: Ask the user for a corrected deadline
   - `ASSIGNEE_AND_QUEUE_CONFLICT`: Tell the user they must choose one
   - `CASEHUB_UNAVAILABLE`: Report failure — do not silently continue or retry

## Output format

Confirm with:
- Work item ID
- Deadline in human-readable form
- Who is responsible (assignee, queue, or "automatic escalation via Watchdog")
