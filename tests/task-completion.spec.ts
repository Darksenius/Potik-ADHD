import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { applyState, collectState } from '../src/services/persistence';
import { handleNativeEvent, preparePendingNativeDeferrals } from '../src/bridge/nativeBridge';
import { isTaskAvailable } from '../src/utils/taskSchedule';
import { fmtDate } from '../src/utils/date';
import { buildForegroundNotifPayload } from '../src/bridge/notifPayload';

test.beforeEach(() => useStore.setState(useStore.getInitialState(), true));

test('completion, undo, persisted reload and duplicate delivery credit an occurrence once', () => {
  const id = useStore.getState().createTask('Відправити лист', 'simple').id;
  useStore.getState().setTaskDone(id, true);
  const credited = collectState();
  expect(credited.tasks[0].done).toBe(true);
  expect(credited.done).toBe(1);
  expect(credited.xpTotal).toBe(10);
  useStore.getState().setTaskDone(id, true);
  useStore.getState().setTaskDone(id, false);
  expect(useStore.getState().tasks[0]).toMatchObject({ done: false });
  expect(useStore.getState().tasks[0].doneDate).toBeUndefined();
  expect(useStore.getState().tasks[0].doneAt).toBeUndefined();
  const undone = JSON.parse(JSON.stringify(collectState()));
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(undone, true)).toBe(true);
  expect(handleNativeEvent('task_done:' + id)).toBe(true);
  expect(handleNativeEvent('task_done:' + id)).toBe(false);
  expect(useStore.getState().xpTotal).toBe(credited.xpTotal);
  expect(useStore.getState().done).toBe(credited.done);

  useStore.getState().duplicateTask(id);
  const copy = useStore.getState().tasks.find(t => t.id !== id)!;
  expect(copy.done).toBe(false);
  expect(copy.doneDate).toBeUndefined();
  useStore.getState().setTaskDone(copy.id, true);
  expect(useStore.getState().xpTotal).toBe(20);
  expect(useStore.getState().done).toBe(2);
});

test('deleting, purging, and hard-deleting alarm tasks clears transient banner and priority references', () => {
  const alarmId = useStore.getState().createTask('Нагадування', 'alarm').id;
  const survivingAlarmId = useStore.getState().createTask('Залишити', 'alarm').id;
  useStore.setState(state => ({ alarmTaskIds: [...state.alarmTaskIds, alarmId, survivingAlarmId], priorities: [alarmId] }));

  useStore.getState().deleteTask(alarmId);
  expect(useStore.getState().alarmTaskIds).toEqual([survivingAlarmId]);
  expect(useStore.getState().priorities).toEqual([null]);
  useStore.getState().restoreTask(alarmId);
  expect(useStore.getState().alarmTaskIds).toEqual([survivingAlarmId]);

  useStore.setState(state => ({
    tasks: state.tasks.map(task => task.id === alarmId ? { ...task, trashed: true, trashedAt: Date.now() - 8 * 86400000 } : task),
    alarmTaskIds: [...state.alarmTaskIds, alarmId],
    priorities: [alarmId],
  }));
  useStore.getState().purgeOldTrash();
  expect(useStore.getState().tasks.some(task => task.id === alarmId)).toBe(false);
  expect(useStore.getState().alarmTaskIds).toEqual([survivingAlarmId]);
  expect(useStore.getState().priorities).toEqual([null]);

  const secondId = useStore.getState().createTask('Інше нагадування', 'alarm').id;
  useStore.setState(state => ({ alarmTaskIds: [...state.alarmTaskIds, secondId], priorities: [secondId] }));
  useStore.getState().hardDeleteTask(secondId);
  expect(useStore.getState().alarmTaskIds).toEqual([survivingAlarmId]);
  expect(useStore.getState().priorities).toEqual([null]);
});

test('a later queued defer is not captured by an earlier completion during startup preflight', () => {
  const requestedAt = Date.now();
  const id = useStore.getState().createTask('Спочатку завершити', 'simple').id;
  const deferral = { kind: 'not-today', requestedAt: requestedAt + 60000, sourceZoneId: 0, targetZoneId: 0, startAt: requestedAt + 23 * 60 * 60000 + 60000 } as const;
  const previousWindow = (globalThis as any).window;
  (globalThis as any).window = { FlowBridge: { getEventEnvelopes: () => JSON.stringify([
    { id: 1, event: 'task_done:' + id, createdAt: requestedAt },
    { id: 2, event: 'task_defer:' + id + ':' + JSON.stringify(deferral), createdAt: requestedAt + 60000 },
  ]) } };

  try {
    preparePendingNativeDeferrals();
    expect(useStore.getState().tasks[0].questDeferral).toBeUndefined();
    expect(handleNativeEvent('task_done:' + id, requestedAt)).toBe(true);
    expect(handleNativeEvent('task_defer:' + id + ':' + JSON.stringify(deferral), requestedAt + 60000)).toBe(false);
    expect(useStore.getState().tasks[0]).toMatchObject({ done: true, questDeferral: undefined, completionDeferral: undefined });
  } finally {
    if (previousWindow === undefined) delete (globalThis as any).window;
    else (globalThis as any).window = previousWindow;
  }
});

test('a queued Done in a new every-zone slot materializes that occurrence before checking done state', () => {
  const now = new Date();
  const today = fmtDate(now);
  const previous = new Date(now);
  previous.setDate(previous.getDate() - 1);
  const previousDay = fmtDate(previous);
  const id = 770;
  useStore.setState({
    zones: [{ id: 77, nm: 'Робота', color: '#fff', prio: 1, slots: [{ s: '00:00', e: '24:00' }] }],
    tasks: [{ id, title: 'Щоденна зона', type: 'zonelinked', done: true, someday: false, zoneId: 77,
      zoneDoneToday: true, zoneOccurrenceKey: previousDay + ':77:00:00', completionCredited: true,
      completionCreditDate: previousDay, doneDate: previousDay, repeat: 'everyzone', repeatDays: [], tags: [],
      created: previousDay + 'T09:00:00' }],
  });

  expect(handleNativeEvent('task_done:' + id, now.getTime())).toBe(true);
  expect(useStore.getState().tasks[0]).toMatchObject({ done: true, zoneDoneToday: true, zoneOccurrenceKey: today + ':77:00:00' });
  expect(useStore.getState().done).toBe(1);
});

test('a delayed every-zone action cannot rewind a newer occurrence or reset unrelated tasks', () => {
  const now = new Date();
  const today = fmtDate(now);
  const earlierSlot = new Date(today + 'T10:00:00');
  const id = 771;
  useStore.setState({
    zones: [{ id: 77, nm: 'Робота', color: '#fff', prio: 1, slots: [{ s: '09:00', e: '12:00' }, { s: '14:00', e: '18:00' }] }],
    tasks: [
      { id, title: 'Стара кнопка', type: 'zonelinked', done: true, someday: false, zoneId: 77,
        zoneDoneToday: true, zoneOccurrenceKey: today + ':77:14:00', completionCredited: true,
        completionCreditDate: today, doneDate: today, repeat: 'everyzone', repeatDays: [], tags: [], created: today + 'T08:00:00' },
      { id: id + 1, title: 'Інша справа', type: 'zonelinked', done: true, someday: false, zoneId: 77,
        zoneDoneToday: true, zoneOccurrenceKey: today + ':77:14:00', completionCredited: true,
        completionCreditDate: today, doneDate: today, repeat: 'everyzone', repeatDays: [], tags: [], created: today + 'T08:00:00' },
    ],
  });

  expect(handleNativeEvent('task_done:' + id, earlierSlot.getTime())).toBe(false);
  expect(useStore.getState().tasks.map(task => task.zoneOccurrenceKey)).toEqual([
    today + ':77:14:00', today + ':77:14:00',
  ]);
  expect(useStore.getState().tasks.every(task => task.done)).toBe(true);
});

test('materializing one queued zone action leaves other tasks on their newer zone occurrence', () => {
  const now = new Date();
  const today = fmtDate(now);
  const earlierSlot = new Date(today + 'T10:00:00');
  const id = 772;
  useStore.setState({
    zones: [{ id: 77, nm: 'Робота', color: '#fff', prio: 1, slots: [{ s: '09:00', e: '12:00' }, { s: '14:00', e: '18:00' }] }],
    tasks: [
      { id, title: 'Крок у першому слоті', type: 'counter', done: false, someday: false, zoneId: 77,
        counter: 0, counterEnabled: true, zoneOccurrenceKey: today + ':77:09:00', repeat: 'everyzone', repeatDays: [], tags: [], created: today + 'T08:00:00' },
      { id: id + 1, title: 'Виконане у другому слоті', type: 'zonelinked', done: true, someday: false, zoneId: 77,
        counter: 7, counterEnabled: true, zoneDoneToday: true, zoneOccurrenceKey: today + ':77:14:00', completionCredited: true,
        completionCreditDate: today, doneDate: today, repeat: 'everyzone', repeatDays: [], tags: [], created: today + 'T08:00:00' },
    ],
  });

  expect(handleNativeEvent('task_inc:' + id, earlierSlot.getTime())).toBe(true);
  expect(useStore.getState().tasks[0]).toMatchObject({ counter: 1, zoneOccurrenceKey: today + ':77:09:00' });
  expect(useStore.getState().tasks[1]).toMatchObject({ done: true, counter: 7, zoneOccurrenceKey: today + ':77:14:00' });
});

test('a duplicate interval Done tap before nextRepeatAt cannot complete the next occurrence retroactively', () => {
  const id = useStore.getState().createTask('Інтервал', 'simple').id;
  const intervalMs = 60_000;
  const firstTapAt = Date.now() - 5 * intervalMs;
  useStore.setState(state => ({ tasks: state.tasks.map(task => task.id === id
    ? { ...task, created: new Date(firstTapAt - 60_000).toISOString(), repeat: 'interval', repeatMs: intervalMs, repeatInterval: 1, repeatUnit: 'min' }
    : task) }));

  expect(handleNativeEvent('task_done:' + id, firstTapAt)).toBe(true);
  const expectedNextAt = firstTapAt + intervalMs;
  expect(useStore.getState().tasks[0].nextRepeatAt).toBe(expectedNextAt);
  expect(expectedNextAt).toBeLessThan(Date.now());

  expect(handleNativeEvent('task_done:' + id, firstTapAt + 30_000)).toBe(false);
  expect(useStore.getState().tasks[0]).toMatchObject({ done: true, nextRepeatAt: expectedNextAt });
  expect(useStore.getState().done).toBe(1);
});

test('undo restores window availability and deleted or journal entries cannot be completed by notification', () => {
  const id = useStore.getState().saveTask(null, { title: 'Вікно', type: 'timewin', planDate: fmtDate(new Date()), windowStart: '00:00', windowEnd: '23:59' });
  useStore.getState().setTaskDone(id, true);
  useStore.getState().setTaskDone(id, false);
  expect(isTaskAvailable(useStore.getState().tasks[0], new Date(fmtDate(new Date()) + 'T12:00:00'))).toBe(true);
  useStore.getState().deleteTask(id);
  expect(handleNativeEvent('task_done:' + id)).toBe(false);
  expect(useStore.getState().tasks[0].done).toBe(false);
  const journalId = useStore.getState().saveTask(null, { title: 'Журнал', type: 'negative', planDate: fmtDate(new Date()) });
  expect(handleNativeEvent('task_done:' + journalId)).toBe(false);
  expect(useStore.getState().tasks.find(t => t.id === journalId)?.counter).toBe(0);
  expect(buildForegroundNotifPayload().taskList.some(t => t.id === journalId)).toBe(false);
});

test('a due repeat earns a new credit, while undoing that repeat cannot earn it twice', () => {
  const id = useStore.getState().saveTask(null, { title: 'Щоденна справа', type: 'simple', planDate: fmtDate(new Date()), repeat: 'daily' });
  useStore.getState().setTaskDone(id, true);
  const nextDay = new Date();
  nextDay.setDate(nextDay.getDate() + 1);
  useStore.getState().resetRepeatingTasksFor(nextDay);
  expect(useStore.getState().tasks[0].done).toBe(false);
  useStore.getState().setTaskDone(id, true);
  useStore.getState().setTaskDone(id, false);
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().xpTotal).toBe(20);
  expect(useStore.getState().done).toBe(2);
});

test('undoing a calendar repeat does not suppress credit on the next due day', () => {
  const id = useStore.getState().saveTask(null, { title: 'Щоденне повернення', type: 'simple', planDate: fmtDate(new Date()), repeat: 'daily' });
  useStore.getState().setTaskDone(id, true);
  useStore.getState().setTaskDone(id, false);
  useStore.getState().resetRepeatingTasksFor(new Date());
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().xpTotal).toBe(10);
  useStore.getState().setTaskDone(id, false);
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  useStore.getState().resetRepeatingTasksFor(tomorrow);
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().xpTotal).toBe(20);
});

test('zone, calendar and task list share completion and undo after reopening', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({
      saveDate: new Date().toDateString(), weekTplSeeded: true, xp: 0, xpTotal: 0, done: 0,
      zones: [{ id: 17, nm: 'Тестова зона', color: '#4f8ef7', slots: [{ s: '00:00', e: '24:00' }] }],
      tasks: [{ id: 91, title: 'Зональна справа', type: 'zonelinked', done: false, someday: false, zoneId: 17,
        planDate: '2026-10-08', repeat: 'none', repeatDays: [], tags: [], created: '2026-10-08T09:00:00.000Z' }],
    }));
  });
  await page.goto('/');
  const todayTask = page.locator('#tasks-list .tc').filter({ has: page.getByRole('button', { name: 'Зональна справа', exact: true }) });
  await todayTask.getByRole('button', { name: 'Виконати: Зональна справа', exact: true }).click();
  await page.clock.runFor(1000);
  await page.reload();
  await page.getByRole('button', { name: 'План', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Повернути: Зональна справа', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Повернути: Зональна справа', exact: true }).click();
  await page.getByRole('button', { name: 'Зараз', exact: true }).click();
  await expect(page.locator('#tasks-list').getByRole('button', { name: 'Виконати: Зональна справа', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  await page.getByRole('button', { name: 'Виконати: Зональна справа', exact: true }).click();
  await page.clock.runFor(1000);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks.find((t: any) => t.id === 91).done).toBe(true);
  expect(saved.done).toBe(1);
  expect(saved.xpTotal).toBe(8);
});
