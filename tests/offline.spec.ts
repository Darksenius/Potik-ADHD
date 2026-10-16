import { test, expect } from '@playwright/test';
import { DAILY_INSIGHTS } from '../src/utils/dailyInsights';

test('core app screens and saved user content work with every nonlocal request blocked', async ({ page }) => {
  const blockedRequests: string[] = [];
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.hostname === '127.0.0.1' || url.hostname === 'localhost') return route.continue();
    blockedRequests.push(url.href);
    return route.abort();
  });
  await page.clock.install({ time: new Date('2026-10-16T12:00:00+03:00') });
  await page.addInitScript(() => {
    if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true }));
  });

  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
  await expect(page.locator('#tabs')).toBeVisible();
  expect(DAILY_INSIGHTS).toHaveLength(100);
  const bundledFonts = await page.evaluate(() => Array.from(document.fonts).map(font => font.family));
  expect(bundledFonts).toContain('Syne');
  expect(bundledFonts).toContain('Space Mono');

  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill('Offline saved task');
  await page.getByRole('button', { name: 'Без дати', exact: true }).click();
  await page.locator('#task-note-section summary').click();
  await page.locator('#task-note').fill('Offline task note');
  await page.locator('#edit-save-fab').click();
  await expect(page.locator('#tasks-list')).toContainText('Offline saved task');
  const savedBeforeReload = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(savedBeforeReload.tasks).toEqual(expect.arrayContaining([expect.objectContaining({ title: 'Offline saved task', note: 'Offline task note' })]));
  await page.clock.runFor(700);
  await page.reload();
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks).toEqual(expect.arrayContaining([expect.objectContaining({ title: 'Offline saved task', note: 'Offline task note' })]));

  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await page.locator('#notepad').fill('Offline quick note');
  await page.getByRole('button', { name: 'Додати до нотаток' }).click();
  const quickNote = page.locator('.note-card').filter({ hasText: 'Offline quick note' });
  await expect(quickNote).toBeVisible();
  await quickNote.getByRole('button', { name: 'Редагувати нотатку' }).click();
  await page.locator('#quick-note-text').fill('Edited offline note');
  await page.getByRole('button', { name: 'Зберегти нотатку' }).click();
  await page.clock.runFor(700);
  await page.reload();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('.note-card').filter({ hasText: 'Edited offline note' })).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await page.locator('.note-card').filter({ hasText: 'Edited offline note' }).getByRole('button', { name: 'Видалити нотатку' }).click();
  await expect(page.locator('.note-card')).toHaveCount(0);

  await page.getByRole('button', { name: 'План', exact: true }).click();
  await expect(page.locator('#planner-sec')).toBeVisible();
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Статистика', exact: true }).click();
  await expect(page.locator('.insight-calendar')).toBeVisible();
  await page.locator('[data-day="2026-10-16"]').click();
  await expect(page.getByRole('dialog')).toContainText(DAILY_INSIGHTS[0].text);
  await page.getByRole('button', { name: 'Гаразд', exact: true }).click();

  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  await expect(page.locator('#settings-page')).toBeVisible();
  await page.locator('.settings-group').filter({ hasText: 'Вигляд' }).locator('summary').click();
  await page.getByLabel('Тема').selectOption('light');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');

  await page.locator('.settings-group').filter({ hasText: 'Про застосунок і допомога' }).locator('summary').click();
  await page.getByRole('button', { name: 'Відкрити довідку' }).click();
  await expect(page.locator('#readme-page')).toBeVisible();
  await expect(page.locator('#readme-page')).toContainText('Потік · Довідка');
  await page.getByRole('button', { name: 'Назад до налаштувань' }).click();
  await page.getByRole('button', { name: 'Що нового' }).click();
  await expect(page.locator('#whats-new-page')).toBeVisible();

  expect(blockedRequests).toEqual([]);
});
