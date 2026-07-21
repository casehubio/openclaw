import { test, expect } from '../fixtures/api-route';
import { gatePending } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test('duplicate start (409) does not corrupt scenario state', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');

  mockServer.setNextResponse('/api/scenarios/trading-oversight/start', 409, { error: 'Already running' });

  const [response] = await Promise.all([
    page.waitForResponse(resp => resp.url().includes('/api/scenarios/trading-oversight/start')),
    page.locator('.scenario-card').first().locator('button', { hasText: 'Start' }).click(),
  ]);
  expect(response.status()).toBe(409);

  // Status should remain idle — no SCENARIO_STARTED event was emitted
  await expect(page.locator('.scenario-card').first().locator('.scenario-status')).toHaveText('idle');
});

test('gate decision failure keeps modal open', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(gatePending('gate-1', 'execution', 'Execute trade', 'high-value', 'risk'));
  await expect(page.locator('pages-modal')).toBeVisible();

  mockServer.setNextResponse('/api/scenarios/trading-oversight/workitems/gate-1/complete', 500, { error: 'Internal server error' });

  await page.locator('approval-gate').locator('button.action-btn', { hasText: 'Approve' }).click();
  await page.locator('blocks-confirm-dialog button.btn-confirm').click();

  // Modal should stay open — user can retry
  await expect(page.locator('pages-modal')).toBeVisible();
});
