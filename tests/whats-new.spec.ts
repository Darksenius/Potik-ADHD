import { test, expect, type Page } from '@playwright/test';

const seed = {
  saveDate: new Date().toDateString(),
  tasks: [{
    id: 401, title: 'Моя особиста справа', type: 'simple', done: false, someday: false,
    created: '2026-10-14T09:00:00.000Z', repeat: 'none', repeatDays: Array(7).fill(false), tags: [],
  }],
  qnotes: [{ id: 402, txt: 'Моя приватна нотатка', folder: 'general', time: '', date: '' }],
};

async function boot(page: Page, theme: 'dark' | 'light') {
  await page.setViewportSize({ width: 320, height: 720 });
  await page.addInitScript(data => localStorage.setItem('flow_v2', JSON.stringify(data)), {
    ...seed,
    preferences: { themeMode: theme, reduceMotion: false, showGamification: true },
  });
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
  await expect.poll(() => page.locator('html').getAttribute('data-theme')).toBe(theme);
}

async function openWhatsNew(page: Page) {
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  await page.getByRole('button', { name: 'Що нового', exact: true }).click();
  await expect(page.locator('#whats-new-page')).toBeVisible();
  await expect(page.locator('#whats-new-page').getByRole('heading', { name: 'Що нового', exact: true })).toBeVisible();
}

async function dataSnapshot(page: Page) {
  return page.evaluate(() => {
    const data = JSON.parse(localStorage.getItem('flow_v2') || '{}');
    return { tasks: data.tasks, qnotes: data.qnotes };
  });
}

test('release notes are newest first, the current note is expanded, and the previous note opens on demand', async ({ page }, info) => {
  await boot(page, 'dark');
  await openWhatsNew(page);

  const pageRoot = page.locator('#whats-new-page');
  const notes = pageRoot.locator('details.release-note');
  await expect(notes).toHaveCount(2);
  await expect(notes.nth(0)).toHaveAttribute('data-version', '1.7.6.r9');
  await expect(notes.nth(0).locator('summary')).toContainText('1.7.6.r9');
  await expect(notes.nth(0).locator('summary')).toContainText('Пояснення змін тепер у самій апці');
  await expect(notes.nth(0)).toHaveAttribute('open', '');
  await expect(notes.nth(1)).toHaveAttribute('data-version', '1.7.6.r8');
  await expect(notes.nth(1).locator('summary')).toContainText('1.7.6.r8');
  await expect(notes.nth(1)).not.toHaveAttribute('open', '');

  const current = notes.nth(0);
  await expect(current.getByText('Пишу це для тебе: що я змінила, як це має працювати й що варто перевірити на твоєму телефоні.', { exact: true })).toBeVisible();
  await expect(current.getByRole('heading', { name: 'Що я змінила', exact: true })).toBeVisible();
  await expect(current.getByRole('heading', { name: 'Що тобі перевірити', exact: true })).toBeVisible();
  const checks = current.locator('.release-check');
  await expect(checks).toHaveCount(3);
  await expect(checks.nth(0).locator('h3')).toHaveText('Відкрити й повернутися');
  await expect(checks.nth(1).locator('h3')).toHaveText('Прочитати без інтернету');
  await expect(checks.nth(2).locator('h3')).toHaveText('Переглянути попередні зміни');
  for (let i = 0; i < 3; i++) {
    await expect(checks.nth(i).locator('ol li').first()).toBeVisible();
    await expect(checks.nth(i).locator('p.release-check-expected')).toContainText('Має бути:');
  }
  const before = await dataSnapshot(page);
  await page.screenshot({ path: info.outputPath('whats-new-dark-narrow.png'), fullPage: true });

  await notes.nth(1).locator('summary').click();
  await expect(notes.nth(1)).toHaveAttribute('open', '');
  await expect(notes.nth(1).getByRole('heading', { name: 'Що я змінила', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Назад до налаштувань', exact: true })).toBeVisible();
  expect(await dataSnapshot(page)).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);

  await page.getByRole('button', { name: 'Назад до налаштувань', exact: true }).click();
  await expect(page.locator('#settings-page')).toBeVisible();
  expect(await dataSnapshot(page)).toEqual(before);
});

test('in-app notes remain readable offline and survive a reload without changing tasks or notes', async ({ page, context }, info) => {
  await boot(page, 'light');
  const before = await dataSnapshot(page);
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();

  await context.setOffline(true);
  await page.getByRole('button', { name: 'Що нового', exact: true }).click();
  const pageRoot = page.locator('#whats-new-page');
  await expect(pageRoot).toBeVisible();
  await expect(pageRoot.locator('details.release-note').first().locator('summary')).toContainText('1.7.6.r9');
  await expect(pageRoot.locator('.release-check').filter({ has: page.getByRole('heading', { name: 'Прочитати без інтернету', exact: true }) })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Назад до налаштувань', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(await dataSnapshot(page)).toEqual(before);
  await page.screenshot({ path: info.outputPath('whats-new-light-narrow.png'), fullPage: true });

  await context.setOffline(false);
  await page.reload();
  await expect(page.locator('#add-fab')).toBeVisible();
  await openWhatsNew(page);
  await expect(page.locator('#whats-new-page').locator('details.release-note').first().locator('summary')).toContainText('1.7.6.r9');
  expect(await dataSnapshot(page)).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
