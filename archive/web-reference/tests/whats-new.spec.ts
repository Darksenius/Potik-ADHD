import { test, expect, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

type ReleaseCheck = { title: string; steps: string[]; expected: string };
type ReleaseNote = { version: string; title: string; message: string; checks: ReleaseCheck[] };
const releaseNotes = JSON.parse(readFileSync(new URL('../src/content/release-notes.json', import.meta.url), 'utf8')) as ReleaseNote[];
const packageVersion = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version.replace('-r', '.r');
const latestRelease = releaseNotes[0];

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
  expect(latestRelease.version).toBe(packageVersion);
  await expect(notes).toHaveCount(releaseNotes.length);
  for (let i = 0; i < releaseNotes.length; i++) {
    await expect(notes.nth(i)).toHaveAttribute('data-version', releaseNotes[i].version);
    await expect(notes.nth(i).locator('summary')).toContainText(releaseNotes[i].version);
    await expect(notes.nth(i).locator('summary')).toContainText(releaseNotes[i].title);
    if (i === 0) await expect(notes.nth(i)).toHaveAttribute('open', '');
    else await expect(notes.nth(i)).not.toHaveAttribute('open', '');
  }
  await expect(notes.nth(0)).toHaveAttribute('open', '');

  const current = notes.nth(0);
  await expect(current.getByText(latestRelease.message, { exact: true })).toBeVisible();
  await expect(current.getByRole('heading', { name: 'Що я змінила', exact: true })).toBeVisible();
  await expect(current.getByRole('heading', { name: 'Що тобі перевірити', exact: true })).toBeVisible();
  const checks = current.locator('.release-check');
  await expect(checks).toHaveCount(latestRelease.checks.length);
  for (let i = 0; i < latestRelease.checks.length; i++) {
    await expect(checks.nth(i).locator('h3')).toHaveText(latestRelease.checks[i].title);
    await expect(checks.nth(i).locator('ol li')).toHaveCount(latestRelease.checks[i].steps.length);
    for (let j = 0; j < latestRelease.checks[i].steps.length; j++) {
      await expect(checks.nth(i).locator('ol li').nth(j)).toHaveText(latestRelease.checks[i].steps[j]);
    }
    await expect(checks.nth(i).locator('p.release-check-expected')).toContainText(latestRelease.checks[i].expected);
  }
  const before = await dataSnapshot(page);
  await page.screenshot({ path: info.outputPath('whats-new-dark-narrow.png'), fullPage: true });

  if (releaseNotes.length > 1) {
    await notes.nth(1).locator('summary').click();
    await expect(notes.nth(1)).toHaveAttribute('open', '');
    await expect(notes.nth(1).locator('.release-personal')).toHaveText(releaseNotes[1].message);
  }
  await expect(page.getByRole('button', { name: 'Назад до налаштувань', exact: true })).toBeVisible();
  expect(await dataSnapshot(page)).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);

  await page.getByRole('button', { name: 'Назад до налаштувань', exact: true }).click();
  await expect(page.locator('#settings-page')).toBeVisible();
  expect(await dataSnapshot(page)).toEqual(before);
});

test('a running app opens bundled notes offline, then an online reload preserves tasks and notes', async ({ page, context }, info) => {
  await boot(page, 'light');
  const before = await dataSnapshot(page);
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();

  await context.setOffline(true);
  await page.getByRole('button', { name: 'Що нового', exact: true }).click();
  const pageRoot = page.locator('#whats-new-page');
  await expect(pageRoot).toBeVisible();
  await expect(pageRoot.locator('details.release-note').first().locator('summary')).toContainText(latestRelease.version);
  await expect(pageRoot.locator('details.release-note').first().locator('.release-personal')).toHaveText(latestRelease.message);
  await expect(page.getByRole('button', { name: 'Назад до налаштувань', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(await dataSnapshot(page)).toEqual(before);
  await page.screenshot({ path: info.outputPath('whats-new-light-narrow.png'), fullPage: true });

  await context.setOffline(false);
  await page.reload();
  await expect(page.locator('#add-fab')).toBeVisible();
  await openWhatsNew(page);
  await expect(page.locator('#whats-new-page').locator('details.release-note').first().locator('summary')).toContainText(latestRelease.version);
  expect(await dataSnapshot(page)).toEqual(before);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
