import { test, expect } from '@playwright/test';

test('private API → device → rule → labeled event, including mobile navigation', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('/');
  await page.getByLabel('API access token').fill('e2e-local-token-at-least-24-characters');
  await page.getByRole('button', { name: 'Connect dashboard' }).click();
  await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
  await page.getByLabel('Device name').fill('Test phone <safe>');
  await page.getByRole('button', { name: 'Register device' }).click();
  await expect(page.getByRole('heading', { name: 'Test phone <safe>' })).toBeVisible();
  await page.getByRole('button', { name: 'Create rule' }).click();
  await expect(page.getByRole('button', { name: 'Disable', exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: 'Generate simulated event' }).click();
  await expect(page.getByText('SIMULATED', { exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: 'Disable', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Enable', exact: true })).toHaveCount(1);
  await page.screenshot({ path: '/tmp/aura-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '≋ Events', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Events', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/aura-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Lock dashboard' }).click();
  await expect(page.getByLabel('API access token')).toBeVisible();
  expect(errors).toEqual([]);
});
