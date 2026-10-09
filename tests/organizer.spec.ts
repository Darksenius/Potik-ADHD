import { test, expect, type Page } from '@playwright/test';
const task = (id: number, title: string, extra = {}) => ({ id, title, type: 'simple', done: false, someday: false, repeat: 'none', repeatDays: Array(7).fill(false), tags: [], created: '2026-10-08T09:00:00.000Z', ...extra });
async function boot(page: Page, data = {}) {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(data => { if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), ...data })); }, data);
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
}
async function scheduled(page: Page, title: string, day: string, time: string) {
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill(title);
  await page.getByRole('button', { name: 'Запланована На точну дату й час' }).click();
  await page.getByLabel('Дата *', { exact: true }).fill(day);
  await page.getByLabel('Час *', { exact: true }).fill(time);
}

test('scheduled task is absent today, visible in its calendar day and uses the same editor/id', async ({ page }, info) => {
  await boot(page);
  await scheduled(page, 'Забрати посилку', '2026-10-09', '10:30');
  await expect(page.locator('.schedule-preview')).toContainText('9 жовтня');
  await page.locator('#edit-save-fab').click();
  await expect(page.locator('#tasks-list')).not.toContainText('Забрати посилку');
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  const day = page.getByRole('button', { name: '9 жовтня 2026 р., є задачі', exact: true });
  await day.click();
  await expect(day.locator('.pd-dot')).toHaveClass(/has/);
  await page.getByRole('button', { name: 'Забрати посилку', exact: true }).click();
  await expect(page.getByLabel('Дата *', { exact: true })).toHaveValue('2026-10-09');
  await page.getByLabel('Час *', { exact: true }).fill('11:30');
  await page.locator('#edit-save-fab').click();
  const state = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(state.tasks).toHaveLength(1);
  expect(state.tasks[0]).toMatchObject({ schedDate: '2026-10-09', planDate: '2026-10-09', schedTime: '11:30', remindBeforeMinutes: 0 });
  await page.screenshot({ path: info.outputPath('planner.png'), fullPage: true, animations: 'disabled' });
});

test('calendar creation keeps selected day and past time requires an explicit choice', async ({ page }) => {
  await boot(page);
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await page.getByRole('button', { name: '+ Задача', exact: true }).click();
  await expect(page.getByLabel('Дата', { exact: true })).toHaveValue('2026-10-08');
  await page.getByLabel('Що зробити?').fill('Ранкова справа');
  await page.getByRole('button', { name: 'Запланована На точну дату й час' }).click();
  await page.getByLabel('Час *', { exact: true }).fill('09:00');
  await page.locator('#edit-save-fab').click();
  await expect(page.getByRole('alert')).toContainText('Цей час уже минув');
  await page.getByLabel('Зберегти з минулим часом').check();
  await page.locator('#edit-save-fab').click();
  await expect(page.locator('.plan-task-row').filter({ hasText: 'Ранкова справа' })).toBeVisible();
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!).tasks[0].schedDate)).toBe('2026-10-08');
});

test('scheduled task rejects missing date/time and snooze saves draft before moving both dates', async ({ page }) => {
  await boot(page);
  await scheduled(page, 'Початкова назва', '2026-10-08', '');
  await page.locator('#edit-save-fab').click();
  await expect(page.getByRole('alert')).toContainText('Вибери дату й час');
  await page.getByLabel('Час *', { exact: true }).fill('14:00');
  await page.locator('#edit-save-fab').click();
  await page.locator('.tc .eb').click();
  await page.getByLabel('Що зробити?').fill('Змінена назва');
  await page.getByText('Додаткові можливості', { exact: true }).click();
  await page.getByRole('button', { name: 'На завтра', exact: true }).click();
  const state = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(state.tasks[0]).toMatchObject({ title: 'Змінена назва', planDate: '2026-10-09', schedDate: '2026-10-09', schedTime: '14:00' });
  await expect(page.locator('#tasks-list')).not.toContainText('Змінена назва');
});

test('all tasks finds hidden future, snoozed, someday and inactive zone tasks', async ({ page }) => {
  await boot(page, { tasks: [task(1, 'Майбутня', { type: 'sched', schedDate: '2026-10-10', schedTime: '10:00' }), task(2, 'Відкладена', { snoozeUntil: 2000000000000 }), task(3, 'Колись', { someday: true }), task(4, 'Неактивна зона', { type: 'zonelinked', zoneId: 999 })] });
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  for (const title of ['Майбутня', 'Відкладена', 'Колись', 'Неактивна зона']) await expect(page.locator('.tt', { hasText: title })).toBeVisible();
  await page.getByLabel('Пошук задач').fill('неактивна');
  await expect(page.locator('#tasks-list .tc')).toHaveCount(1);
});

test('week shift keeps selected weekday in view, Today restores current week', async ({ page }) => {
  await boot(page);
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await page.getByRole('button', { name: 'Наступний →' }).click();
  await expect(page.locator('.pdd-date')).toContainText('15 Жов');
  await expect(page.locator('.plan-day.sel .pd-num')).toHaveText('15');
  await page.getByRole('button', { name: 'Сьогодні', exact: true }).click();
  await expect(page.locator('.pdd-date')).toContainText('8 Жов');
});

test('inbox captures simple task and settings persist independently of legacy theme', async ({ page }, info) => {
  await boot(page);
  await page.getByRole('button', { name: '↓ Вхідні', exact: true }).click();
  await page.getByLabel('Нова проста задача').fill('Не забути зарядку');
  await page.getByRole('button', { name: 'Додати', exact: true }).click();
  await expect(page.locator('.tt')).toHaveText('Не забути зарядку');
  await page.getByRole('button', { name: '⚙ Налаштування', exact: true }).click();
  await page.getByLabel('Тема', { exact: true }).selectOption('light');
  await page.getByLabel('Зменшити анімації').check();
  await page.getByLabel('Показувати досвід і рівень').uncheck();
  await page.clock.runFor(1000);
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await expect(page.locator('html')).toHaveAttribute('data-reduce-motion', 'true');
  await expect(page.locator('#xp-badge')).toHaveCount(0);
  await page.locator('#add-fab').click();
  await page.getByRole('button', { name: 'Запланована На точну дату й час' }).click();
  await page.screenshot({ path: info.outputPath('editor-light.png'), fullPage: true, animations: 'disabled' });
});

test('overnight window is visible at 23:00 but hidden during daytime', async ({ page }) => {
  await boot(page, { tasks: [task(1, 'Нічна задача', { type: 'timewin', windowStart: '22:00', windowEnd: '06:00' })] });
  await expect(page.locator('#tasks-list')).not.toContainText('Нічна задача');
  await page.clock.setSystemTime(new Date('2026-10-08T23:00:00+03:00'));
  await page.clock.runFor(61000);
  await expect(page.locator('#tasks-list .tt')).toHaveText('Нічна задача');
});

test('day-only zone edit opens real inputs and cancel keeps original day rules', async ({ page }) => {
  await boot(page, { weekTplSeeded: true });
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  const edit = page.getByTitle('Змінити час лише цього дня', { exact: true }).first();
  await edit.click();
  await page.getByLabel('Початок зони', { exact: true }).fill('05:00');
  await page.getByRole('button', { name: 'Скасувати', exact: true }).click();
  await expect(edit).toBeVisible();
  await edit.click();
  await expect(page.getByLabel('Початок зони', { exact: true })).not.toHaveValue('05:00');
  await page.getByLabel('Початок зони', { exact: true }).fill('05:00');
  await page.getByRole('button', { name: 'Зберегти час', exact: true }).click();
  await page.clock.runFor(1000);
  const data = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(data.planDayZones['2026-10-08'][0].s).toBe('05:00');
  expect(data.zones.find((z: any) => z.id === data.planDayZones['2026-10-08'][0].zoneId).slots[0].s).not.toBe('05:00');
});
