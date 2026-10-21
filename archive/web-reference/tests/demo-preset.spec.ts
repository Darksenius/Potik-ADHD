import { test, expect } from '@playwright/test';

test('Settings applies the local today/tomorrow demo once, preserves user data, and reloads it', async ({ page }) => {
  // 20:59 UTC is 23:59 in Kyiv on this date, so today and tomorrow cross midnight locally.
  await page.clock.install({ time: new Date('2026-10-16T20:59:50Z') });
  await page.addInitScript(() => {
    if (localStorage.getItem('flow_v2')) return;
    localStorage.setItem('flow_v2', JSON.stringify({
      saveDate: new Date().toDateString(),
      tasks: [{ id: 77, title: 'Моя наявна задача', type: 'simple', done: false, someday: false,
        created: '2026-10-01T09:00:00.000Z', note: 'Моя нотатка', repeat: 'none', repeatDays: [], tags: [] }],
      nid: 78, qnid: 100, xp: 31, xpTotal: 31, progressDays: ['2026-10-15'],
      planDayZones: { '2026-10-16': [{ zoneId: 77, s: '06:00', e: '07:00' }] },
      dayTemplates: [{ id: 'user-template', name: 'Existing schedule', blocks: [{ zoneId: 77, s: '05:00', e: '06:00' }] }],
      planRules: [{ id: 'tomorrow-rule', name: 'Tomorrow', start: '2026-10-17', end: '2026-10-17', days: [{ tplId: 'user-template', rest: false }] }],
    }));
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  const preset = page.locator('.settings-demo');
  await preset.locator('summary').click();
  await expect(preset.locator('summary')).toContainText('Ознайомчий пресет завдань');
  await expect(preset).toContainText('16.10.2026');
  await expect(preset).toContainText('17.10.2026');

  const apply = preset.getByRole('button', { name: 'Додати ознайомчі приклади' });
  await apply.click();
  const readSaved = () => page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2') || '{}'));
  await expect.poll(async () => (await readSaved()).tasks?.filter((task: { dayPresetKey?: string }) => task.dayPresetKey?.startsWith('potik-intro-v1:')).length).toBe(18);
  const first = await readSaved();
  const demo = first.tasks.filter((task: { dayPresetKey?: string }) => task.dayPresetKey?.startsWith('potik-intro-v1:'));
  expect(new Set(demo.filter((task: { planDate: string }) => task.planDate === '2026-10-16').map((task: { type: string }) => task.type))).toEqual(
    new Set(['simple', 'sched', 'check', 'counter', 'note', 'alarm', 'timewin', 'ctx', 'negative', 'zonelinked']));
  expect(new Set(demo.map((task: { repeat: string }) => task.repeat))).toEqual(
    new Set(['none', 'daily', 'weekly', 'weekdays', 'weekend', 'interval', 'custom', 'everyzone']));
  expect(first.tasks.find((task: { id: number }) => task.id === 77)).toMatchObject({ title: 'Моя наявна задача', note: 'Моя нотатка' });
  expect(first.xp).toBe(31);
  expect(first.progressDays).toEqual(['2026-10-15']);
  expect(new Set(first.qnotes.map((note: { id: number }) => note.id)).size).toBe(3);
  expect(first.qnotes.every((note: { date: string }) => note.date === '')).toBe(true);
  expect(first.planDayZones['2026-10-16']).toEqual(expect.arrayContaining([
    { zoneId: 77, s: '06:00', e: '07:00' },
    expect.objectContaining({ s: '07:00', e: '12:00' }),
    expect.objectContaining({ s: '13:00', e: '18:00' }),
  ]));
  expect(first.planDayZones['2026-10-17']).toEqual(expect.arrayContaining([
    { zoneId: 77, s: '05:00', e: '06:00' },
    expect.objectContaining({ s: '07:00', e: '12:00' }),
    expect.objectContaining({ s: '13:00', e: '18:00' }),
  ]));
  expect(first.qnotes).toHaveLength(3);

  await apply.click();
  await expect.poll(async () => (await readSaved()).tasks?.filter((task: { dayPresetKey?: string }) => task.dayPresetKey?.startsWith('potik-intro-v1:')).length).toBe(18);
  await expect.poll(async () => (await readSaved()).zones?.filter((zone: { nm: string }) => zone.nm.endsWith('· демо')).length).toBe(2);

  await page.reload();
  await expect(page.getByRole('button', { name: 'Налаштування', exact: true })).toBeVisible();
  const reloaded = await readSaved();
  expect(reloaded.tasks.filter((task: { dayPresetKey?: string }) => task.dayPresetKey?.startsWith('potik-intro-v1:'))).toHaveLength(18);
  expect(reloaded.tasks.find((task: { id: number }) => task.id === 77)).toMatchObject({ title: 'Моя наявна задача', note: 'Моя нотатка' });
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('.note-card')).toHaveCount(3);
  await expect(page.locator('.note-card').filter({ hasText: /DEMO · Сьогодні/ })).toBeVisible();
  expect((await page.locator('.note-card time').allTextContents()).every(text => !/Invalid Date|NaN/.test(text))).toBe(true);
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  await page.locator('.settings-demo summary').click();
  await page.locator('.settings-demo').getByRole('button', { name: 'Прибрати ознайомчі задачі (18)' }).click();
  await expect.poll(async () => (await readSaved()).tasks?.filter((task: { dayPresetKey?: string }) => task.dayPresetKey?.startsWith('potik-intro-v1:')).length).toBe(0);
  const afterCleanup = await readSaved();
  expect(afterCleanup.tasks).toHaveLength(1);
  expect(afterCleanup.tasks[0]).toMatchObject({ id: 77, title: 'Моя наявна задача', note: 'Моя нотатка' });
  expect(afterCleanup.qnotes).toHaveLength(3);
  expect(afterCleanup.zones.filter((zone: { nm: string }) => zone.nm.endsWith('· демо'))).toHaveLength(2);
  expect(afterCleanup.xp).toBe(31);
  expect(afterCleanup.progressDays).toEqual(['2026-10-15']);
});
