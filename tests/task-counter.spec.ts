import { test, expect, type Page } from '@playwright/test';
import { useStore } from '../src/state/store';
import { applyState, collectState, releaseSaveGuard } from '../src/services/persistence';
import { fmtDate } from '../src/utils/date';

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  releaseSaveGuard();
});

async function savedState(page: Page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
}

function reloadStore() {
  const saved = JSON.parse(JSON.stringify(collectState()));
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(saved, true)).toBe(true);
}

test('water seed is retired once; a user-created counter can be created, counted and reopened', async ({ page }, info) => {
  await page.clock.install({ time: new Date('2026-10-13T12:00:00+03:00') });
  await page.goto('/');
  await page.clock.runFor(1600);
  const fresh = await savedState(page);
  expect(fresh.tasks.filter((t: any) => !t.trashed && t.title === 'Вода')).toHaveLength(0);
  expect(fresh.preferences.waterSeedRetired).toBe(true);

  // Retire the existing default entry into recoverable trash and remove its legacy routine.
  await page.addInitScript(() => {
    if (sessionStorage.getItem('legacy-water-retired')) return;
    localStorage.setItem('flow_v2', JSON.stringify({
      saveDate: new Date().toDateString(), weekTplSeeded: true,
      tasks: [{ id: 301, title: 'Вода', type: 'simple', done: false, someday: false,
        created: new Date().toISOString(), repeat: 'daily', repeatDays: [], tags: [],
        counterEnabled: true, counterStep: 100, counterUnit: 'ml', counterTarget: 0, counter: 200 }],
      recur: [{ id: 'r1', nm: 'Вода', unit: 'ml', val: 200, step: 100 }],
    }));
    sessionStorage.setItem('legacy-water-retired', 'seeded');
  });
  await page.reload();
  await page.clock.runFor(1600);
  const retired = await savedState(page);
  expect(retired.tasks).toHaveLength(1);
  expect(retired.tasks[0]).toMatchObject({ id: 301, title: 'Вода', trashed: true, counter: 200 });
  expect(retired.tasks[0].trashedAt).toEqual(expect.any(Number));
  expect(retired.recur).toEqual([]);
  expect(retired.preferences.waterSeedRetired).toBe(true);

  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?', { exact: true }).fill('Вода');
  await page.locator('summary').filter({ hasText: 'Додаткові можливості' }).click();
  await page.getByLabel('Лічильник прогресу', { exact: true }).check();
  await page.getByLabel('Крок + / −', { exact: true }).fill('100');
  await page.locator('#task-counter-unit').selectOption('ml');
  await page.getByLabel('Ціль лічильника (0 — без цілі)', { exact: true }).fill('0');
  await page.getByLabel('Повторення', { exact: true }).selectOption('daily');
  await page.getByRole('button', { name: 'Створити задачу', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Нова задача' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Розгорнути: Вода', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Зменшити: Вода', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Збільшити: Вода', exact: true }).click();
  await page.getByRole('button', { name: 'Збільшити: Вода', exact: true }).click();
  await page.getByRole('button', { name: 'Зменшити: Вода', exact: true }).click();
  await expect(page.locator('#tasks-list .cvv')).toHaveText('100 мл');
  await expect(page.getByRole('button', { name: 'Виконати: Вода', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await page.clock.runFor(700);
  const counted = await savedState(page);
  expect(counted.tasks.filter((t: any) => !t.trashed)).toHaveLength(1);
  expect(counted.tasks.find((t: any) => t.id === 301)).toMatchObject({ trashed: true, title: 'Вода' });
  expect(counted.tasks[0]).toMatchObject({
    title: 'Вода', type: 'simple', counterEnabled: true, counterStep: 100,
    counterUnit: 'ml', counterTarget: 0, counter: 100, counterCreditedProgress: 200,
    repeat: 'daily', done: false,
  });
  expect(counted.xpTotal).toBe(4);
  await page.reload();
  const reopened = await savedState(page);
  expect(reopened.preferences.waterSeedRetired).toBe(true);
  expect(reopened.tasks.filter((t: any) => !t.trashed && t.title === 'Вода')).toHaveLength(1);
  expect(reopened.tasks.find((t: any) => t.id === 301)?.trashed).toBe(true);
  await expect(page.locator('#tasks-list .cvv')).toHaveText('100 мл');
  await page.setViewportSize({ width: 320, height: 800 });
  await expect(page.getByRole('button', { name: 'Зменшити: Вода', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Збільшити: Вода', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: info.outputPath('water-counter-320.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: 'Збільшити: Вода', exact: true }).click();
  await page.clock.runFor(700);
  expect((await savedState(page)).xpTotal).toBe(4);
  await page.locator('#tasks-list .tc')
    .filter({ has: page.getByRole('button', { name: 'Вода', exact: true }) })
    .locator('.task-title-button').click();
  if (!await page.getByLabel('Крок + / −', { exact: true }).isVisible()) {
    await page.locator('summary').filter({ hasText: 'Додаткові можливості' }).click();
  }
  await expect(page.getByLabel('Тип задачі', { exact: true })).toHaveValue('simple');
  await expect(page.getByLabel('Лічильник прогресу', { exact: true })).toBeChecked();
  await expect(page.getByLabel('Крок + / −', { exact: true })).toHaveValue('100');
  await expect(page.locator('#task-counter-unit')).toHaveValue('ml');
  await expect(page.getByLabel('Ціль лічильника (0 — без цілі)', { exact: true })).toHaveValue('0');
});

test('counter undo cannot farm progress or goal credit; completion is manual and copies and daily repeats start clean', () => {
  const id = useStore.getState().saveTask(null, {
    title: 'Рух', type: 'simple', planDate: fmtDate(new Date()), repeat: 'daily',
    counterEnabled: true, counterStep: 100, counterUnit: 'count', counterTarget: 200,
  });
  const task = () => useStore.getState().tasks.find(t => t.id === id)!;
  useStore.getState().changeCounter(id, -100);
  expect(task().counter).toBe(0);
  expect(useStore.getState().xpTotal).toBe(0);
  useStore.getState().changeCounter(id, 100);
  useStore.getState().changeCounter(id, -100);
  useStore.getState().changeCounter(id, 100);
  expect(useStore.getState().xpTotal).toBe(2);
  useStore.getState().changeCounter(id, 100);
  expect(task()).toMatchObject({ counter: 200, cntHit: true, done: false });
  expect(useStore.getState().xpTotal).toBe(19);
  useStore.getState().changeCounter(id, -100);
  reloadStore();
  useStore.getState().changeCounter(id, 100);
  expect(useStore.getState().xpTotal).toBe(19);
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().xpTotal).toBe(29);
  useStore.getState().changeCounter(id, 100);
  expect(task().counter).toBe(200);
  useStore.getState().setTaskDone(id, false);
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().xpTotal).toBe(29);
  expect(useStore.getState().done).toBe(1);

  useStore.getState().duplicateTask(id);
  const copy = useStore.getState().tasks.find(t => t.id !== id)!;
  expect(copy).toMatchObject({
    counter: 0, cntHit: false, counterCreditedProgress: 0, done: false,
    completionCredited: false, counterEnabled: true, counterTarget: 200, counterStep: 100,
  });
  expect(copy.counterDay).toBeUndefined();
  useStore.getState().changeCounter(copy.id, 100);
  expect(task().counter).toBe(200);
  expect(useStore.getState().xpTotal).toBe(31);

  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1);
  useStore.getState().resetRepeatingTasksFor(tomorrow);
  expect(task()).toMatchObject({ counter: 0, cntHit: false, counterCreditedProgress: 0, done: false, completionCredited: false });
  useStore.getState().changeCounter(id, 100);
  expect(useStore.getState().xpTotal).toBe(33);
});

test('checklist edits and removals preserve item credit while undo, copies and next-day reset remain independent', () => {
  const id = useStore.getState().saveTask(null, {
    title: 'Перед виходом', type: 'check', planDate: fmtDate(new Date()), repeat: 'daily',
  });
  const task = () => useStore.getState().tasks.find(t => t.id === id)!;
  for (const text of ['Паспорт', 'Ключі', 'Квиток']) useStore.getState().addChecklistItem(id, text);
  useStore.getState().toggleChecklistItem(id, 0);
  useStore.getState().toggleChecklistItem(id, 0);
  reloadStore();
  useStore.getState().toggleChecklistItem(id, 0);
  expect(useStore.getState().xpTotal).toBe(3);
  useStore.getState().editChecklistItem(id, 0, '  Документи  ');
  useStore.getState().deleteChecklistItem(id, 1);
  expect(task().items).toEqual([
    { text: 'Документи', done: true, credited: true }, { text: 'Квиток', done: false },
  ]);
  useStore.getState().toggleChecklistItem(id, 1);
  expect(task().done).toBe(false);
  expect(useStore.getState().xpTotal).toBe(6);
  useStore.getState().setTaskDone(id, true);
  useStore.getState().toggleChecklistItem(id, 0);
  expect(task().items?.every(item => item.done)).toBe(true);
  expect(useStore.getState().xpTotal).toBe(16);
  useStore.getState().setTaskDone(id, false);
  useStore.getState().toggleChecklistItem(id, 0);
  useStore.getState().toggleChecklistItem(id, 0);
  expect(useStore.getState().xpTotal).toBe(16);

  useStore.getState().duplicateTask(id);
  const copy = useStore.getState().tasks.find(t => t.id !== id)!;
  expect(copy.items).toEqual([
    { text: 'Документи', done: false, credited: false }, { text: 'Квиток', done: false, credited: false },
  ]);
  useStore.getState().editChecklistItem(copy.id, 0, 'Документи для копії');
  useStore.getState().toggleChecklistItem(copy.id, 0);
  expect(task().items?.[0]).toEqual({ text: 'Документи', done: true, credited: true });
  expect(useStore.getState().xpTotal).toBe(19);
  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1);
  useStore.getState().resetRepeatingTasksFor(tomorrow);
  expect(task().items).toEqual([
    { text: 'Документи', done: false, credited: false }, { text: 'Квиток', done: false, credited: false },
  ]);
  useStore.getState().toggleChecklistItem(id, 0);
  expect(useStore.getState().xpTotal).toBe(22);
});

test('native counters, snooze and explicit captures retry atomically and acknowledge once across reopen', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-13T12:00:00+03:00') });
  await page.addInitScript(() => {
    const restored = sessionStorage.getItem('counter-command-test');
    const skipRequestedAt = Date.now();
    const mock = (window as any).__counterCommands = restored ? JSON.parse(restored) : {
      fail: true,
      persisted: JSON.stringify({
        saveDate: new Date().toDateString(), weekTplSeeded: true, recur: [], qnotes: [], xp: 0, xpTotal: 0, done: 0,
        tasks: [
          { id: 501, title: 'Вода зі шторки', type: 'simple', done: false, someday: false, expanded: true,
            planDate: '2026-10-13', repeat: 'daily', repeatDays: [], tags: [], created: new Date(skipRequestedAt - 60000).toISOString(),
            counterEnabled: true, counterStep: 100, counterUnit: 'ml', counterTarget: 0,
            counter: 200, counterCreditedProgress: 200, counterDay: '2026-10-13' },
          { id: 502, title: 'Завершити зі шторки', type: 'simple', done: false, someday: false,
            planDate: '2026-10-13', repeat: 'none', repeatDays: [], tags: [], created: new Date(skipRequestedAt - 60000).toISOString() },
        ],
      }),
      skipRequestedAt,
      pending: [
        { id: 11, event: 'task_inc:501', createdAt: Date.now() },
        { id: 12, event: 'task_dec:501', createdAt: Date.now() },
        { id: 13, event: 'task_inc:501', createdAt: Date.now() },
        { id: 14, event: 'task_skip:501', createdAt: skipRequestedAt },
        { id: 15, event: 'task_done:502', createdAt: Date.now() },
        { id: 16, event: 'capture_task:  ... Задача зі шторки  ', createdAt: Date.now() },
        { id: 17, event: 'capture_note:... Нотатка зі шторки\nДругий рядок', createdAt: Date.now() },
        { id: 18, event: 'task_inc:501', createdAt: Date.now() - 86400000 },
      ],
      commits: [] as { ids: number[]; state: string }[],
    };
    (window as any).FlowBridge = {
      load: () => mock.persisted, save: () => {}, saveNotif: () => {},
      writeBackup: () => {}, readBackup: () => '', hasBackup: () => false,
      saveWithEvents: (json: string, ids: string) => {
        if (mock.fail) return false;
        const ack: number[] = JSON.parse(ids);
        mock.persisted = json;
        if (ack.length) mock.commits.push({ ids: ack, state: json });
        mock.pending = mock.pending.filter((event: { id: number }) => !ack.includes(event.id));
        return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {}, stop: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.pending) }),
      drainEvents: async () => { throw new Error('Destructive queue drain must not be used'); },
    } } };
  });
  await page.goto('/');
  await page.clock.runFor(2000);
  const failed = await page.evaluate(() => (window as any).__counterCommands);
  expect(failed.pending).toHaveLength(8);
  expect(failed.commits).toEqual([]);
  expect(JSON.parse(failed.persisted).tasks[0].counter).toBe(200);
  expect(JSON.parse(failed.persisted).qnotes).toEqual([]);
  await expect(page.locator('#tasks-list .cvv')).toHaveText('200 мл');
  await expect(page.getByRole('button', { name: 'Виконати: Завершити зі шторки', exact: true })).toHaveAttribute('aria-pressed', 'false');

  const retryAt = await page.evaluate(() => {
    (window as any).__counterCommands.fail = false;
    window.__flowDrainEvents?.();
    window.__flowDrainEvents?.();
    return Date.now();
  });
  await expect.poll(() => page.evaluate(() => (window as any).__counterCommands.commits.length)).toBe(1);
  const committed = await page.evaluate(() => (window as any).__counterCommands);
  expect(committed.pending).toEqual([]);
  expect(committed.commits[0].ids).toEqual([11, 12, 13, 14, 15, 16, 17, 18]);
  const saved = JSON.parse(committed.commits[0].state);
  const water = saved.tasks.find((t: any) => t.id === 501);
  expect(water).toMatchObject({ counter: 300, counterCreditedProgress: 300, done: false, someday: false });
  expect(water).toMatchObject({
    snoozeUntil: 0,
    questDeferral: {
      kind: 'next-zone',
      requestedAt: committed.skipRequestedAt,
      sourceZoneId: 3,
      targetZoneId: 4,
      startAt: new Date('2026-10-13T13:00:00+03:00').getTime(),
      endAt: new Date('2026-10-13T14:00:00+03:00').getTime(),
    },
  });
  expect(saved.tasks.find((t: any) => t.id === 502).done).toBe(true);
  expect(saved.xpTotal).toBe(23);
  expect(saved.done).toBe(1);
  const captured = saved.tasks.filter((t: any) => t.title === '... Задача зі шторки');
  expect(captured).toHaveLength(1);
  expect(captured[0].planDate).toBeUndefined();
  expect(saved.qnotes).toHaveLength(2);
  expect(saved.qnotes.some((n: any) => n.txt === '... Нотатка зі шторки\nДругий рядок' && n.folder === 'impulse')).toBe(true);
  expect(saved.qnotes.some((n: any) => n.txt.includes('task_inc:501') && n.txt.includes('день уже змінився'))).toBe(true);

  await page.getByRole('button', { name: 'Вхідні', exact: true }).click();
  await expect(page.locator('#inbox-sec')).toContainText('... Задача зі шторки');
  await page.evaluate(() => {
    window.__flowDrainEvents?.();
    sessionStorage.setItem('counter-command-test', JSON.stringify((window as any).__counterCommands));
  });
  await page.reload();
  await page.clock.runFor(2000);
  const reloaded = await page.evaluate(() => (window as any).__counterCommands);
  expect(reloaded.commits).toHaveLength(1);
  expect(reloaded.pending).toEqual([]);
  const final = JSON.parse(reloaded.persisted);
  expect(final.tasks.find((t: any) => t.id === 501).counter).toBe(300);
  expect(final.tasks.filter((t: any) => t.title === '... Задача зі шторки')).toHaveLength(1);
  expect(final.qnotes).toHaveLength(2);
  expect(final.xpTotal).toBe(23);
  expect(final.done).toBe(1);
});
