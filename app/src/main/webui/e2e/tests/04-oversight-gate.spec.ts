import { test, expect } from '../fixtures/api-route';
import { gatePending, gateResolved } from '../fixtures/events';

const SCENARIOS = [
  { id: 'trading-oversight', name: 'Trading Oversight', description: 'Trade execution', status: 'idle' as const },
];

test('gate pending shows modal with action and classification', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(gatePending('gate-1', 'execution', 'Execute trade #1234', 'high-value', 'risk'));
  await expect(page.locator('pages-modal')).toBeVisible();
  await expect(page.locator('approval-gate')).toContainText('Execute trade #1234');
  await expect(page.locator('approval-gate')).toContainText('high-value');
});

test('approve gate sends PUT and modal dismisses on resolution', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(gatePending('gate-1', 'execution', 'Execute trade #1234', 'high-value', 'risk'));
  await expect(page.locator('pages-modal')).toBeVisible();

  // Click approve — opens confirmation dialog
  await page.locator('approval-gate').locator('button.action-btn', { hasText: 'Approve' }).click();

  // Confirm in the dialog (button text mirrors the action name)
  await page.locator('blocks-confirm-dialog button.btn-confirm').click();

  // Verify PUT was recorded
  await expect.poll(() => mockServer.getRecordedCalls().some(c =>
    c.path.includes('/workitems/gate-1/complete')
  )).toBeTruthy();

  mockServer.emitEvent(gateResolved('gate-1', 'approved'));
  await expect(page.locator('pages-modal')).not.toBeVisible();
});

test('reject gate sends PUT with reject outcome', async ({ page, mockServer }) => {
  mockServer.setScenarios(SCENARIOS);
  await page.goto('/');
  await page.locator('li', { hasText: 'Trading Oversight' }).click();
  await mockServer.waitForFreshSSEClient();

  mockServer.emitEvent(gatePending('gate-1', 'execution', 'Execute trade #1234', 'high-value', 'risk'));
  await expect(page.locator('pages-modal')).toBeVisible();

  await page.locator('approval-gate').locator('button.action-btn', { hasText: 'Reject' }).click();

  // Confirm in the dialog (button text mirrors the action name)
  await page.locator('blocks-confirm-dialog button.btn-confirm').click();

  await expect.poll(() => {
    const calls = mockServer.getRecordedCalls();
    return calls.some(c => c.path.includes('/workitems/gate-1/complete') && c.body?.outcome === 'reject');
  }).toBeTruthy();

  mockServer.emitEvent(gateResolved('gate-1', 'rejected'));
  await expect(page.locator('pages-modal')).not.toBeVisible();
});
