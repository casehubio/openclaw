import { test, expect } from '../fixtures/api-route';
import { channelMessage } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test('channel messages appear in feed', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(channelMessage('signal', 'Signal Analyst', 'Analysing trade #1234 — USD/EUR 50M'));
  await expect(page.locator('channel-feed')).toContainText('Analysing trade #1234');

  mockServer.emitEvent(channelMessage('risk', 'Risk Assessor', 'Risk assessment: exposure within limits'));
  await expect(page.locator('channel-feed')).toContainText('Risk assessment: exposure within limits');
});
