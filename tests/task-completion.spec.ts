import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { applyState, collectState } from '../src/services/persistence';
import { handleNativeEvent } from '../src/bridge/nativeBridge';
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
  const zone = page.locator('#zone-tasks-banner');
  await zone.getByRole('button', { name: 'Виконати: Зональна справа', exact: true }).click();
  await expect(zone.getByRole('button', { name: 'Позначити невиконаною: Зональна справа', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.clock.runFor(1000);
  await page.reload();
  await expect(zone.getByRole('button', { name: 'Позначити невиконаною: Зональна справа', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '📅 План', exact: true }).click();
  await page.getByRole('button', { name: 'Повернути: Зональна справа', exact: true }).click();
  await page.getByRole('button', { name: '☀ Сьогодні', exact: true }).click();
  await expect(zone.getByRole('button', { name: 'Виконати: Зональна справа', exact: true })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  await page.getByRole('button', { name: 'Виконати: Зональна справа', exact: true }).click();
  await page.clock.runFor(1000);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks.find((t: any) => t.id === 91).done).toBe(true);
  expect(saved.done).toBe(1);
  expect(saved.xpTotal).toBe(8);
});
