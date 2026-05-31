---
name: casehub-case
description: Open a CaseHub case for a complex multi-step workflow with human governance gates
version: 1.0.0
triggers:
  - "start a case for"
  - "this is a multi-step process"
  - "I need to manage this workflow"
  - "create a case plan for"
  - "open a governed workflow"
  - "this needs human oversight"
  - "manage this end to end"
tools:
  - casehub_rest_client
permissions: []
---

You are opening a CaseHub case — a structured multi-step workflow with human governance
gates, SLA enforcement, and audit trail.

## When to use this

Use `casehub-case` when a task is too complex for a single work item:
- Multiple sequential steps with dependencies
- Human approval required at one or more stages
- Multiple agents or people involved
- Budget or contract decisions required

For simple tasks with a single deadline, use `casehub-workitem` instead.

## Procedure

1. **Search for a matching CasePlanModel:**

```bash
casehub_rest_client.sh GET "/engine/plans?q=USER_INTENT_DESCRIPTION"
```

2. **If zero plans match:**
   - Fetch the full plan list: `casehub_rest_client.sh GET /engine/plans`
   - Present the available plans to the user and ask them to select the closest match
   - Do not guess — if no plan fits, tell the user and suggest creating a work item instead

3. **If one or more plans match:**
   - Use the best match (first result)
   - Confirm with the user: "I found a plan called [NAME]. Does this match what you need?"
   - On confirmation, open the case:

```bash
casehub_rest_client.sh POST /engine/cases \
  '{"planId": "PLAN_ID", "description": "USER_DESCRIPTION"}'
```

4. **On success:** Report the case ID and explain that CaseHub now orchestrates the next steps.
   "Case opened (ID: case-xyz). CaseHub will manage the workflow from here — you'll be called
   when action is required."

5. **On error:**
   - `CASEHUB_UNAVAILABLE`: Report failure
   - Any other error: Report verbatim

## Important

Once the case is open, CaseHub orchestrates subsequent steps via direct calls to OpenClaw.
You shift from autonomous agent to orchestrated executor — do not take further independent
action on this workflow unless explicitly called by CaseHub.
