import { test, expect } from '../fixtures/api-route';
import { agentStarted, agentCompleted } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test.beforeEach(async ({ mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
});

test('agent appears on AGENT_STARTED with running badge', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  const worker = page.locator('.worker').first();
  await expect(worker.locator('.worker-name')).toHaveText('signal');
  await expect(worker.locator('.worker-role')).toHaveText('Signal Analyst');
  await expect(worker.locator('.worker-state')).toHaveText('running');
  await expect(worker.locator('.worker-state')).toHaveClass(/running/);
});

test('agent transitions to completed with duration', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  mockServer.emitEvent(agentCompleted('signal', 'Signal Analyst', 'completed', 4200));
  const worker = page.locator('.worker').first();
  await expect(worker.locator('.worker-state')).toHaveText('completed');
  await expect(worker.locator('.worker-state')).toHaveClass(/completed/);
  await expect(worker.locator('.worker-duration')).toContainText('Completed in 4.2s');
});

test('multiple agents appear in pipeline order', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  mockServer.emitEvent(agentCompleted('signal', 'Signal Analyst', 'completed', 4200));
  mockServer.emitEvent(agentStarted('risk', 'Risk Assessor'));

  const workers = page.locator('.worker');
  await expect(workers).toHaveCount(2);
  await expect(workers.nth(0).locator('.worker-name')).toHaveText('signal');
  await expect(workers.nth(1).locator('.worker-name')).toHaveText('risk');
});

test('failed outcome shows failed badge and duration text', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('risk', 'Risk Assessor'));
  mockServer.emitEvent(agentCompleted('risk', 'Risk Assessor', 'failed', 1500));
  await expect(page.locator('.worker-state')).toHaveText('failed');
  await expect(page.locator('.worker-state')).toHaveClass(/failed/);
  await expect(page.locator('.worker-duration')).toContainText('Failed after 1.5s');
});

test('declined outcome shows warning styling and duration text', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  mockServer.emitEvent(agentCompleted('signal', 'Signal Analyst', 'declined', 800));
  await expect(page.locator('.worker-state')).toHaveText('declined');
  await expect(page.locator('.worker-state')).toHaveClass(/declined/);
  await expect(page.locator('.worker-duration')).toContainText('Declined after 0.8s');
});

test('delegated outcome shows warning styling and duration text', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  mockServer.emitEvent(agentCompleted('signal', 'Signal Analyst', 'delegated', 1200));
  await expect(page.locator('.worker-state')).toHaveText('delegated');
  await expect(page.locator('.worker-state')).toHaveClass(/delegated/);
  await expect(page.locator('.worker-duration')).toContainText('Delegated after 1.2s');
});

test('timeout outcome shows danger styling and duration text', async ({ page, mockServer }) => {
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(agentStarted('signal', 'Signal Analyst'));
  mockServer.emitEvent(agentCompleted('signal', 'Signal Analyst', 'timeout', 30000));
  await expect(page.locator('.worker-state')).toHaveText('timeout');
  await expect(page.locator('.worker-state')).toHaveClass(/timeout/);
  await expect(page.locator('.worker-duration')).toContainText('Timed out after 30.0s');
});
