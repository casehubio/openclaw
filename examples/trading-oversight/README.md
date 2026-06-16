# Trading Oversight Example

**Built on:** [OpenClaw for Trading: Complete 2026 Guide](https://openclawforge.com/blog/openclaw-for-trading-complete-2026-guide-automated-trading-ai-agents/)

The tutorial shows a signal → risk → execution pipeline that places orders
automatically. This example runs the same pipeline with CaseHub: the execution
agent is ready to place a $89,200 position — and it **asks first**.

## What CaseHub adds

- Every agent step is a tracked commitment with a formal outcome
- The Execution agent cannot place the order without human sign-off
- Both approve and reject paths are recorded in the audit trail
- If the human rejects, the trade is declined with a full ledger record

## The wow moment

Signal confirmed. Risk assessed. Execution agent is holding the order — $89,200,
MEDIUM risk, stop-loss at $871. It's waiting for you. Run `./approve.sh` to fill
or `./approve.sh reject` to decline.

## Running

```bash
cp .env.example .env
# Edit .env — add your ANTHROPIC_API_KEY
docker compose up -d
./scenario.sh
# When the gate fires:
./approve.sh          # approve the trade
./approve.sh reject   # decline the trade
```

## Mock services

| Service | Port | What it simulates |
|---------|------|-------------------|
| mock-broker | 5001 | Broker REST API (portfolio, order execution) |
| mock-feed | 5002 | Market data feed (NVDA OHLCV + momentum score) |

## Agents

| Agent | Gates? | Key tools |
|-------|--------|-----------|
| Signal | No (agentId="signal" ≠ "execution") | `casehub_done` |
| Risk | No (agentId="risk" ≠ "execution") | `casehub_done`, `casehub_reject` |
| Execution | **Yes** (agentId="execution" == "execution") | `casehub_done` |
