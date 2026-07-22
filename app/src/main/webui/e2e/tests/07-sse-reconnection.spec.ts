import { test, expect } from '../fixtures/api-route';
import { agentStarted } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test('SSE reconnection recovers and delivers new events', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  // Initial agent via SSE
  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  await expect(page.locator('.worker')).toHaveCount(1);

  // Set snapshot for potential reconnection backfill
  mockServer.setStateSnapshot('trading-oversight', {
    scenarioId: 'trading-oversight',
    status: 'running',
    agents: [
      { agentId: 'signal', role: 'Signal Analyst', state: 'completed', durationMs: 4200 },
      { agentId: 'risk', role: 'Risk Assessor', state: 'running', durationMs: null },
    ],
    pendingGate: null,
    recentMessages: [],
  });

  // Disconnect and reconnect
  mockServer.disconnectSSE();
  mockServer.reconnectSSE();

  // Wait for EventSource auto-reconnect and fresh SSE client
  await mockServer.waitForFreshSSEClient();

  // Verify new events arrive after reconnection
  mockServer.emitEvent(agentStarted('execution', 'Trade Executor'));
  await expect(page.locator('.worker-name', { hasText: 'execution' })).toBeVisible();
});

test('SSE reconnection backfills state from server', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  // Initial: one running agent
  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  await expect(page.locator('.worker')).toHaveCount(1);

  // Snapshot reflects state change that happened during disconnect
  mockServer.setStateSnapshot('trading-oversight', {
    scenarioId: 'trading-oversight',
    status: 'running',
    agents: [
      { agentId: 'signal', role: 'Signal Analyst', state: 'completed', durationMs: 4200 },
      { agentId: 'risk', role: 'Risk Assessor', state: 'running', durationMs: null },
    ],
    pendingGate: null,
    recentMessages: [],
  });

  // Disconnect and reconnect — onopen should trigger loadState()
  mockServer.disconnectSSE();
  mockServer.reconnectSSE();
  await mockServer.waitForFreshSSEClient();

  // Verify backfill: should now show 2 agents from the snapshot
  await expect(page.locator('.worker')).toHaveCount(2);
  await expect(page.locator('.worker').nth(0).locator('.worker-state')).toHaveText('completed');
  await expect(page.locator('.worker').nth(1).locator('.worker-name')).toHaveText('risk');
});
