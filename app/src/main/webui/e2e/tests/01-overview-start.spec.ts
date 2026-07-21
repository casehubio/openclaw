import { test, expect } from '../fixtures/api-route';
import { scenarioStarted } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution with human oversight gates', status: 'idle' as const },
  { id: 'multi-agent-dev-team', name: 'Dev Team', description: 'Multi-agent software development', status: 'idle' as const },
  { id: 'incident-response', name: 'Incident Response', description: 'Automated incident triage and response', status: 'idle' as const },
];

test('scenario cards load with idle status', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  const cards = page.locator('.scenario-card');
  await expect(cards).toHaveCount(3);
  await expect(cards.first().locator('.scenario-status')).toHaveText('idle');
});

test('start button triggers POST and SSE updates status', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');

  const [response] = await Promise.all([
    page.waitForResponse(resp => resp.url().includes('/api/scenarios/trading-oversight/start')),
    page.locator('.scenario-card').first().locator('button', { hasText: 'Start' }).click(),
  ]);
  expect(response.status()).toBe(202);

  await expect.poll(() => mockServer.getRecordedCalls()).toHaveLength(1);
  expect(mockServer.getRecordedCalls()[0].path).toBe('/api/scenarios/trading-oversight/start');

  mockServer.emitEvent(scenarioStarted('trading-oversight'));
  await expect(page.locator('.scenario-card').first().locator('.scenario-status')).toHaveText('running');
  await expect(page.locator('.scenario-card').first().locator('button', { hasText: 'View' })).toBeVisible();
});
