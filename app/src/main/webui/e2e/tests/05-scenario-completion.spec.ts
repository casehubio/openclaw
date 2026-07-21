import { test, expect } from '../fixtures/api-route';
import { scenarioStarted, scenarioCompleted, scenarioFailed } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test('scenario completed shows success banner', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(scenarioStarted());
  mockServer.emitEvent(scenarioCompleted());
  const banner = page.locator('.status-banner');
  await expect(banner).toContainText('COMPLETED');
  await expect(banner).toHaveClass(/completed/);
});

test('scenario failed shows failure banner', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(scenarioStarted());
  mockServer.emitEvent(scenarioFailed('Agent timeout'));
  const banner = page.locator('.status-banner');
  await expect(banner).toContainText('FAILED');
  await expect(banner).toHaveClass(/failed/);
});
