import { test, expect, type Page } from '@playwright/test';

async function boot(page: Page, data: object = {}) {
  await page.clock.install({ time: new Date('2026-09-16T12:00:00+03:00') });
  await page.addInitScript(data => {
    if (!localStorage.getItem('flow_v2')) {
      localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), ...data }));
    }
  }, data);
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
}

const task = (id: number, title: string, extra = {}) => ({
  id, title, type: 'simple', done: false, someday: false, repeat: 'none',
  repeatDays: [false, false, false, false, false, false, false], tags: [],
  created: '2026-09-16T09:00:00.000Z', ...extra,
});

test('editor loads the selected task and clears cancelled drafts', async ({ page }) => {
  page.on('dialog', d => d.accept());
  await boot(page, { tasks: [task(401, 'Перша'), task(402, 'Друга')] });
  await page.locator('.tc').filter({ hasText: 'Перша' }).locator('.eb').click();
  await expect(page.locator('#edit-body input').first()).toHaveValue('Перша');
  await page.locator('#edit-body input').first().fill('Не зберігати');
  await page.getByRole('button', { name: '← Назад' }).click();
  await page.locator('.tc').filter({ hasText: 'Друга' }).locator('.eb').click();
  await expect(page.locator('#edit-body input').first()).toHaveValue('Друга');
  await page.getByRole('button', { name: '← Назад' }).click();
  await page.locator('#add-fab').click();
  await expect(page.locator('#edit-body input').first()).toHaveValue('');
});

test('new task survives reload within seconds, before minute autosave', async ({ page }) => {
  await boot(page);
  await page.clock.runFor(2000);
  await page.locator('#add-fab').click();
  await page.locator('#edit-body input').first().fill('Не загубити');
  await page.locator('#edit-save-fab').click();
  await page.clock.runFor(2000);
  await page.reload();
  await expect(page.locator('.tt').filter({ hasText: 'Не загубити' })).toBeVisible();
});

test('planner selects the local calendar day near midnight', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-09-16T00:30:00+03:00') });
  await page.goto('/');
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await expect(page.locator('.pdd-date')).toContainText('16 Вер');
});

test('interval tasks become available again without restarting', async ({ page }) => {
  await boot(page, { tasks: [task(401, 'Повтор', {
    done: true, doneDate: '2026-09-16', repeat: 'interval', repeatMs: 60000,
    nextRepeatAt: new Date('2026-09-16T12:01:00+03:00').getTime(),
  })] });
  await page.clock.runFor(61000);
  await expect(page.locator('.tt').filter({ hasText: 'Повтор' })).toBeVisible();
});

test('alarm displays in the app at the configured time', async ({ page }) => {
  await boot(page, { tasks: [task(401, 'Час перерви', { type: 'alarm', alarmTime: '12:01', alarmFired: false })] });
  await page.clock.runFor(61000);
  await expect(page.getByRole('alert').filter({ hasText: 'Час перерви' })).toBeVisible();
});

test('all sections remain reachable through the four primary tabs and render without runtime errors or horizontal page overflow', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await boot(page);
  for (const label of ['☀ Сьогодні', '📝 Блокнот', '📅 План', '🫀 Стан', '🕐 Зони', '💡 Ідеї', '📊 Стат']) {
    await openTab(page, label);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), label).toBe(true);
  }
  expect(errors).toEqual([]);
});

test('all 13 task types can be created in separate editor sessions', async ({ page }) => {
  await boot(page);
  const types = ['simple', 'check', 'counter', 'note', 'alarm', 'sched', 'timewin', 'pomodoro', 'habit', 'kid', 'ctx', 'negative', 'zonelinked'];
  for (const type of types) {
    await page.locator('#add-fab').click();
    await expect(page.locator('#edit-body input').first()).toHaveValue('');
    await page.locator('#edit-body input').first().fill('Тест ' + type);
    await page.getByText('Додаткові можливості', { exact: true }).click();
    await page.getByLabel('Тип задачі', { exact: true }).selectOption(type);
    if (type === 'sched') await page.getByLabel('Час *', { exact: true }).fill('15:30');
    if (type === 'alarm') await page.getByLabel('Час будильника', { exact: true }).fill('16:00');
    if (type === 'zonelinked') await page.getByLabel('Зона', { exact: true }).selectOption('3');
    await page.locator('#edit-save-fab').click();
  }
  await page.clock.runFor(2000);
  const typesSaved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!).tasks.map((t: { type: string }) => t.type).sort());
  expect(typesSaved).toEqual(types.sort());
});

test('native notification events are subscribed and drained on startup', async ({ page }) => {
  await page.addInitScript(() => {
    (window as any).__testNative = { listeners: [], drains: 0 };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      start: async () => {}, update: async () => {}, stop: async () => {},
      addListener: (name: string) => { (window as any).__testNative.listeners.push(name); return Promise.resolve({ remove: async () => {} }); },
      drainEvents: async () => { (window as any).__testNative.drains++; return { events: '[]' }; },
    } } };
  });
  await boot(page);
  await page.evaluate(() => document.dispatchEvent(new Event('deviceready')));
  await page.clock.runFor(2000);
  expect(await page.evaluate(() => (window as any).__testNative.listeners)).toContain('flowEvent');
  expect(await page.evaluate(() => (window as any).__testNative.drains)).toBeGreaterThan(0);
});

test('day journal stays attached to its selected day', async ({ page }) => {
  await boot(page);
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  const journal = page.getByPlaceholder('Запиши, що згадав про цей день…');
  await journal.fill('Запис середи');
  await page.locator('.plan-day').filter({ has: page.locator('.pd-num', { hasText: /^17$/ }) }).click();
  await expect(journal).toHaveValue('');
  await journal.fill('Запис четверга');
  await page.locator('.plan-day').filter({ has: page.locator('.pd-num', { hasText: /^16$/ }) }).click();
  await expect(journal).toHaveValue('Запис середи');
});

test('rest-day toggle immediately updates the calendar', async ({ page }) => {
  await boot(page);
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await page.locator('.rest-toggle').click();
  await expect(page.locator('.plan-day.sel')).toHaveClass(/rest-day/);
});

test('zones can be edited and disabled without losing their tasks', async ({ page }) => {
  await boot(page, { weekTplSeeded: true, tasks: [task(401, 'Задача зони', { zoneId: 3, zoneName: 'Робота', zoneColor: '#7ed321' })] });
  await openTab(page, '🕐 Зони');
  await page.getByRole('button', { name: 'Редагувати зону Робота', exact: true }).click();
  await page.getByLabel('Назва зони').fill('Робочий фокус');
  await page.getByLabel('Початок 1', { exact: true }).fill('10:00');
  await page.getByRole('button', { name: 'Зберегти зону', exact: true }).click();
  await page.getByRole('button', { name: 'Вимкнути Робочий фокус', exact: true }).click();
  await page.clock.runFor(1000);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.zones.find((z: any) => z.id === 3)).toMatchObject({ nm: 'Робочий фокус', off: true, slots: [{ s: '10:00', e: '13:00' }, { s: '14:00', e: '18:00' }] });
  expect(saved.tasks[0]).toMatchObject({ title: 'Задача зони', zoneId: 3, zoneName: 'Робочий фокус' });
});

test('deleting a zone task removes it from the active banner', async ({ page }) => {
  await boot(page, { weekTplSeeded: true, tasks: [task(401, 'Прибрати банер', { type: 'zonelinked', zoneId: 3 })] });
  await page.locator('#zone-tasks-banner').getByTitle('Видалити', { exact: true }).click();
  await expect(page.locator('#zone-tasks-banner')).toHaveCount(0);
});

test('legacy saved state keeps task details, notes, statistics and planner data', async ({ page }) => {
  const legacy = {
    tasks: [task(900, 'Існуючий чекліст', { type: 'check', items: [{ text: 'Зберегти пункт', done: true }] })],
    qnotes: [{ id: 501, txt: 'Давня нотатка', folder: 'impulse', date: '2026-09-15', time: '09:00' }],
    notepad: 'Важливий блокнот', xp: 31, xpTotal: 731, level: 4, done: 15,
    theme: 'light', weekTplSeeded: true,
    planRestDays: { '2026-09-18': true }, planSchedules: { '2026-09-19': 'custom' },
    planDayLog: { '2026-09-15': { focus: [], energy: 2, routineDone: {}, priorities: [], tasksDone: 3, note: 'Історія' } },
  };
  await boot(page, legacy);
  await page.clock.runFor(2000);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved).toMatchObject(legacy);
  expect(saved.nid).toBeGreaterThan(900);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
});

test('midnight resets daily tasks and routine while preserving one-off completion', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-09-16T23:59:50+03:00') });
  await page.addInitScript(data => localStorage.setItem('flow_v2', JSON.stringify(data)), {
    saveDate: 'Wed Sep 16 2026', tasks: [task(401, 'Щодня', { done: true, doneDate: '2026-09-16', repeat: 'daily' }), task(402, 'Разова', { done: true, doneDate: '2026-09-16' })],
    recur: [{ id: 'water', nm: 'Вода', val: 4, done: true, unit: 'count', step: 1, color: '#fff' }],
  });
  await page.goto('/');
  await page.clock.runFor(12000);
  await expect(page.locator('.tt').filter({ hasText: 'Щодня' })).toBeVisible();
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks.find((t: any) => t.id === 402).done).toBe(true);
  expect(saved.recur[0]).toMatchObject({ val: 0, done: false });
});

test('pending Android backup is not overwritten by startup autosave', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-09-16T12:00:00+03:00') });
  await page.addInitScript(() => {
    (window as any).__writes = [];
    (window as any).FlowBridge = { load: () => '', hasBackup: () => true,
      readBackup: () => JSON.stringify({ tasks: [], saveDate: 'Tue Sep 15 2026' }),
      save: () => {}, saveNotif: () => {}, writeBackup: (s: string) => (window as any).__writes.push(s),
    };
  });
  await page.goto('/');
  await expect(page.getByText('Знайдено резервну копію', { exact: true })).toBeVisible();
  await page.clock.runFor(61000);
  expect(await page.evaluate(() => (window as any).__writes)).toEqual([]);
});

test('5/2 schedule applies for two weeks and a manual exception affects only one day', async ({ page }) => {
  await boot(page);
  page.on('dialog', d => d.accept());
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await page.locator('.wkc-head').click();
  await page.locator('.wkc-row').filter({ hasText: '5/2' }).getByRole('button', { name: '▶', exact: true }).click();
  await page.locator('.wkc-panel input[type=date]').fill('2026-09-14');
  await page.locator('.wkc-panel select').selectOption('14');
  await page.getByRole('button', { name: 'Застосувати', exact: true }).click();
  await page.locator('.rest-toggle').click();
  await page.clock.runFor(1000);
  const data = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(data.planRules[0]).toMatchObject({ start: '2026-09-14', end: '2026-09-27', name: '5/2' });
  expect(data.planRules[0].days.map((d: any) => d.rest)).toEqual([false, false, false, false, false, true, true]);
  expect(data.planRestDays).toEqual({ '2026-09-16': true });
});

test('checklist, counter and Pomodoro actions award experience and persist', async ({ page }) => {
  await boot(page, { tasks: [
    task(401, 'Чекліст', { type: 'check', expanded: true, items: [{ text: 'Пункт', done: false }] }),
    task(402, 'Лічильник', { type: 'counter', expanded: true, counter: 0, counterTarget: 2 }),
    task(403, 'Таймер', { type: 'pomodoro', expanded: true, pomSecs: 2, pomMode: 'work', pomRunning: false, pomSessions: 0 }),
  ] });
  await page.locator('.ci input').check();
  await page.locator('.cw').getByRole('button', { name: '+', exact: true }).click();
  await page.locator('.cw').getByRole('button', { name: '+', exact: true }).click();
  await page.getByRole('button', { name: '▶ Старт', exact: true }).click();
  await page.clock.runFor(3000);
  const data = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(data.tasks[0].items[0].done).toBe(true);
  expect(data.tasks[1]).toMatchObject({ counter: 2, cntHit: true });
  expect(data.tasks[2]).toMatchObject({ pomMode: 'break', pomSessions: 1, pomRunning: false });
  expect(data.xpTotal).toBe(37);
});

test('scheduled reminders fire once and trashed alarms stay silent', async ({ page }) => {
  await boot(page, { tasks: [
    task(401, 'Зустріч', { type: 'sched', schedDate: '2026-09-16', schedTime: '12:01', firedSched: false }),
    task(402, 'У кошику', { type: 'alarm', alarmTime: '12:01', trashed: true, trashedAt: Date.now() }),
  ] });
  await page.clock.runFor(61000);
  await expect(page.getByRole('alert')).toContainText('Зустріч');
  await expect(page.getByRole('alert')).not.toContainText('У кошику');
  await page.getByRole('button', { name: 'Зрозуміло', exact: true }).click();
  await page.clock.runFor(5000);
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('notification navigation returns from help to tasks', async ({ page }) => {
  await boot(page);
  await page.getByRole('button', { name: '? Довідка', exact: true }).click();
  await page.evaluate(() => (window as any).swTab('tasks'));
  await expect(page.locator('#add-fab')).toBeVisible();
});

test('mobile screens at 320 and 390 pixels remain usable with screenshots', async ({ page }, testInfo) => {
  await boot(page, { tasks: [task(401, 'Довга назва задачі для перевірки перенесення тексту без втрати кнопок та горизонтального прокручування')] });
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 844 });
    for (const label of ['☀ Сьогодні', '📝 Блокнот', '📅 План', '🫀 Стан', '🕐 Зони']) {
      await openTab(page, label);
      await page.clock.runFor(400);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), width + ' ' + label).toBe(true);
      await page.screenshot({ path: testInfo.outputPath(width + '-' + label.slice(3) + '.png'), fullPage: true });
    }
  }
});

test('accidentally deleted tasks can be restored from trash', async ({ page }) => {
  await boot(page, { tasks: [task(401, 'Повернути задачу')] });
  await page.locator('#tasks-list .del').click();
  await page.getByText('Кошик (1)', { exact: true }).click();
  await page.getByRole('button', { name: 'Відновити', exact: true }).click();
  await expect(page.locator('#tasks-list .tt')).toHaveText('Повернути задачу');
  await page.clock.runFor(1000);
  await page.reload();
  await expect(page.locator('#tasks-list .tt')).toHaveText('Повернути задачу');
});

test('new routine supports millilitres instead of forcing a count', async ({ page }) => {
  await boot(page);
  await openTab(page, '🫀 Стан');
  await page.getByRole('button', { name: '+ Корисна', exact: true }).click();
  await page.getByLabel('Назва звички').fill('Чай');
  await page.getByLabel('Одиниця виміру').selectOption('ml');
  await page.getByRole('button', { name: 'Зберегти звичку', exact: true }).click();
  await page.locator('.rc-item').filter({ hasText: 'Чай' }).getByRole('button', { name: '+100мл', exact: true }).click();
  await page.clock.runFor(1000);
  const data = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(data.recur.find((r: any) => r.nm === 'Чай')).toMatchObject({ unit: 'ml', step: 100, val: 100 });
});

async function openTab(page: Page, label: string) {
  if (!['☀ Сьогодні', '📅 План', '↓ Вхідні', '⋯ Ще'].includes(label)) await page.getByRole('button', { name: '⋯ Ще', exact: true }).click();
  await page.getByRole('button', { name: label, exact: true }).click();
}
