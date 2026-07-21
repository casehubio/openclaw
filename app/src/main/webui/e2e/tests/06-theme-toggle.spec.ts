import { test, expect } from '../fixtures/api-route';

test('theme toggle switches dark to light and back', async ({ page }) => {
  await page.goto('/');

  const getThemeToken = () => page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--pages-neutral-1').trim());

  const token1 = await getThemeToken();
  expect(token1).not.toBe('');

  await page.locator('button', { hasText: 'Toggle Theme' }).click();
  const token2 = await getThemeToken();
  expect(token2).not.toBe(token1);

  await page.locator('button', { hasText: 'Toggle Theme' }).click();
  const token3 = await getThemeToken();
  expect(token3).toBe(token1);
});
