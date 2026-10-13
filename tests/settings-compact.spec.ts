import { test, expect } from '@playwright/test';

const incompatibleBackup = JSON.stringify({
  saveDate: 'Mon Oct 12 2026',
  tasks: [{ id: 9, title: 'Old habit', type: 'habit' }],
});

test('compact settings, recovery archive export, and help return path', async ({ page }) => {
  await page.addInitScript(raw => {
    if (localStorage.getItem('flow_v2') === null) localStorage.setItem('flow_v2', raw);
  }, incompatibleBackup);
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();

  const navButtons = page.locator('#nav button');
  await expect(navButtons).toHaveCount(2);
  await expect(navButtons.nth(0)).toHaveText('Потік');
  await expect(navButtons.nth(1)).toHaveText('Налаштування');
  await navButtons.nth(1).click();

  const groups = page.locator('#settings-page details.settings-group');
  await expect(groups).toHaveCount(4);
  await expect(groups.locator('summary').nth(0)).toContainText('Вигляд');
  await expect(groups.locator('summary').nth(1)).toContainText('Сповіщення');
  await expect(groups.locator('summary').nth(2)).toContainText('Дані та резервні копії');
  await expect(groups.locator('summary').nth(3)).toContainText('Про застосунок і допомога');
  for (let i = 0; i < 4; i++) expect(await groups.nth(i).evaluate(el => el.hasAttribute('open'))).toBe(false);

  await groups.nth(2).locator('summary').click();
  const archiveGroup = groups.nth(2);
  await expect(archiveGroup.getByRole('heading', { name: 'Архів несумісних даних' })).toBeVisible();
  const downloadPromise = page.waitForEvent('download');
  await archiveGroup.getByRole('button', { name: 'Експортувати архів' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('flow-recovery-archive.json');

  await groups.nth(3).locator('summary').click();
  await groups.nth(3).getByRole('button', { name: 'Відкрити довідку' }).click();
  await expect(page.getByRole('heading', { name: 'Що доречно зараз?' })).toBeVisible();
  await expect(page.locator('#readme-page').getByText(/У «Зараз» видно до п’яти/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Назад до налаштувань' })).toBeVisible();
  await page.getByRole('button', { name: 'Назад до налаштувань' }).click();
  await expect(page.locator('#settings-page')).toBeVisible();
});
