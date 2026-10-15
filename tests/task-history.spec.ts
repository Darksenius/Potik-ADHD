import { test, expect } from '@playwright/test';
import type { Task } from '../src/types';
import { useStore } from '../src/state/store';
import { fmtDate } from '../src/utils/date';
import { completedTaskOccurrences, historyEvent } from '../src/utils/taskHistory';
import { taskDoneOn, taskOccursOn } from '../src/utils/taskSchedule';
import { collectState, applyState } from '../src/services/persistence';
import { mergeState } from '../src/services/exportImport';

const task = (patch: Partial<Task> = {}): Task => ({
  id: 734,
  title: 'Щоденна справа',
  type: 'simple',
  done: false,
  someday: false,
  repeat: 'none',
  repeatDays: Array(7).fill(false),
  tags: [],
  created: '2026-09-20T09:00:00',
  ...patch,
});

const dayAtOffset = (offset: number) => {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + offset);
  return fmtDate(date);
};

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
});

test('a completed repeat remains checked on its past planner day after lifecycle reset advances the task', () => {
  const yesterday = dayAtOffset(-1);
  const today = dayAtOffset(0);
  useStore.setState({ tasks: [task({
    repeat: 'daily',
    planDate: yesterday,
    done: true,
    doneDate: yesterday,
    doneAt: '10:15',
    completionCredited: true,
    completionCreditDate: yesterday,
    occurrenceId: 'daily-yesterday',
  })] });

  useStore.getState().resetRepeatingTasksFor(new Date(`${today}T12:00:00`));

  const state = useStore.getState();
  const current = state.tasks[0];
  expect(current).toMatchObject({ done: false, planDate: today });
  expect(taskOccursOn(current, yesterday)).toBe(false);
  expect(taskDoneOn(current, yesterday)).toBe(false); // old callers retain their pure/live behavior
  expect(taskDoneOn(current, yesterday, state.taskHistory)).toBe(true);
  expect(taskDoneOn(current, today, state.taskHistory)).toBe(false);
});

test('same-day completed repeat occurrences stay distinct and do not check the live occurrence', () => {
  const yesterday = dayAtOffset(-1);
  const today = dayAtOffset(0);
  const current = task({ repeat: 'everyzone', occurrenceId: 'zone-current-pending' });
  const history = [
    historyEvent(current, 'completed', new Date(`${yesterday}T09:10:00`).getTime(), 'yesterday-1', 0, yesterday, '09:10'),
    historyEvent(current, 'completed', new Date(`${yesterday}T13:10:00`).getTime(), 'yesterday-2', 1, yesterday, '13:10'),
    historyEvent(current, 'completed', new Date(`${today}T09:10:00`).getTime(), 'today-1', 2, today, '09:10'),
    historyEvent(current, 'completed', new Date(`${today}T13:10:00`).getTime(), 'today-2', 3, today, '13:10'),
  ];

  expect(completedTaskOccurrences(history)).toHaveLength(4);
  expect(completedTaskOccurrences(history).filter(event => event.date === yesterday)).toHaveLength(2);
  expect(taskDoneOn(current, yesterday, history)).toBe(true);
  expect(taskDoneOn(current, today, history)).toBe(false);
});

test('Undo reopens only the active repeat occurrence and preserves an earlier completion', () => {
  const yesterday = dayAtOffset(-1);
  const today = dayAtOffset(0);
  const id = 735;
  useStore.setState({ tasks: [task({ id, repeat: 'daily', planDate: yesterday })], nid: id + 1 });

  useStore.getState().setTaskDone(id, true, new Date(`${yesterday}T09:00:00`).getTime());
  useStore.getState().resetRepeatingTasksFor(new Date(`${today}T12:00:00`));
  useStore.getState().setTaskDone(id, true, new Date(`${today}T10:00:00`).getTime());
  const activeOccurrence = useStore.getState().tasks[0].occurrenceId;
  useStore.getState().setTaskDone(id, false, new Date(`${today}T10:05:00`).getTime());

  const state = useStore.getState();
  const completed = completedTaskOccurrences(state.taskHistory);
  expect(state.tasks[0]).toMatchObject({ done: false, planDate: today, occurrenceId: activeOccurrence });
  expect(completed.map(event => event.date)).toEqual([yesterday]);
  expect(completed[0].occurrenceId).not.toBe(activeOccurrence);
  expect(state.taskHistory.filter(event => event.occurrenceId === activeOccurrence).map(event => event.kind)).toEqual(['completed', 'reopened']);
});

test('task history survives collect/apply reload roundtrip', () => {
  const id = 736;
  const savedTask = task({ id, title: 'Збережений факт' });
  useStore.setState({ tasks: [savedTask], nid: id + 1 });
  useStore.getState().setTaskDone(id, true);
  const saved = JSON.parse(JSON.stringify(collectState()));
  const expectedHistory = saved.taskHistory;

  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(saved, true)).toBe(true);
  expect(collectState().taskHistory).toEqual(expectedHistory);
  expect(completedTaskOccurrences(useStore.getState().taskHistory).map(event => event.title)).toEqual(['Збережений факт']);
});

test('rename and hard delete keep the original completion snapshot', () => {
  const id = useStore.getState().saveTask(null, { title: 'Стара назва', type: 'simple' });
  useStore.getState().setTaskDone(id, true);
  const occurrenceId = useStore.getState().taskHistory[0].occurrenceId;

  useStore.getState().saveTask(id, { title: 'Нова назва', type: 'simple' });
  expect(useStore.getState().taskHistory[0]).toMatchObject({ title: 'Стара назва', occurrenceId });
  useStore.getState().hardDeleteTask(id);

  expect(useStore.getState().tasks.some(item => item.id === id)).toBe(false);
  expect(completedTaskOccurrences(useStore.getState().taskHistory)).toMatchObject([
    { taskId: id, title: 'Стара назва', occurrenceId },
  ]);
});

test('daily log counts only completion events dated today', () => {
  const yesterday = dayAtOffset(-1);
  const today = dayAtOffset(0);
  const oldCompletion = historyEvent(task({ id: 737, title: 'Вчорашня справа' }), 'completed',
    new Date(`${yesterday}T18:30:00`).getTime(), 'old-completion', 0, yesterday, '18:30');
  useStore.setState({ tasks: [], taskHistory: [oldCompletion], planDayLog: {} });

  useStore.getState().logDayData();

  expect(useStore.getState().planDayLog[today]?.tasksDone).toBe(0);
  expect(useStore.getState().dayStats(today).tasksDone).toBe(0);
});

test('a malformed history ledger is rejected without partially changing state', () => {
  const existing = task({ id: 738, title: 'Залишити без змін' });
  useStore.setState({ tasks: [existing], notepad: 'Цей текст має лишитися' });
  const before = collectState();
  const malformed = JSON.parse(JSON.stringify(before));
  malformed.taskHistory.push({
    ...historyEvent(existing, 'completed', Date.now(), 'malformed-event', 0),
    kind: 'unknown-action',
  });

  expect(applyState(malformed, true)).toBe(false);
  expect(collectState()).toEqual(before);
  expect(useStore.getState().notepad).toBe('Цей текст має лишитися');
});

test('merge remaps task and zone history references and reimport is idempotent', () => {
  const today = dayAtOffset(0);
  const zoneId = 9;
  const created = '2026-09-21T09:00:00';
  const currentZone = { id: zoneId, nm: 'Моя зона', color: '#123456', slots: [] };
  const local = task({ id: 31, title: 'Локальна задача', zoneId, created });
  const imported = task({
    id: 31,
    title: 'Імпортована задача',
    zoneId,
    created,
    repeat: 'everyzone',
    zoneOccurrenceKey: `${today}:${zoneId}:18:00`,
    occurrenceId: `31@${created}|zone:${today}:${zoneId}:18:00`,
  });
  const importedEvent = historyEvent(imported, 'completed', new Date(`${today}T18:20:00`).getTime(),
    imported.occurrenceId!, 4, today, '18:20');
  const backup = {
    zones: [{ id: zoneId, nm: 'Імпортована зона', color: '#abcdef', slots: [] }],
    tasks: [imported],
    taskHistory: [importedEvent],
  };
  useStore.setState({ zones: [currentZone], tasks: [local], taskHistory: [], nid: 32 });

  expect(mergeState(backup)).toBeGreaterThan(0);
  const state = useStore.getState();
  const importedZone = state.zones.find(zone => zone.nm === 'Імпортована зона');
  const importedTask = state.tasks.find(item => item.title === 'Імпортована задача');
  const event = state.taskHistory.find(item => item.title === 'Імпортована задача');
  expect(importedZone?.id).not.toBe(zoneId);
  expect(importedTask?.id).not.toBe(31);
  expect(importedTask?.zoneId).toBe(importedZone?.id);
  expect(event).toMatchObject({ taskId: importedTask?.id, zoneId: importedZone?.id });
  expect(event?.occurrenceId).toContain(`|zone:${today}:${importedZone?.id}:18:00`);

  expect(mergeState(backup)).toBe(0);
  expect(useStore.getState().tasks.filter(item => item.title === 'Імпортована задача')).toHaveLength(1);
  expect(useStore.getState().taskHistory.filter(item => item.title === 'Імпортована задача')).toHaveLength(1);
});

test('merging an older completion cannot override a later local Undo for the same occurrence', () => {
  const day = dayAtOffset(-1);
  const id = 739;
  const created = '2026-09-22T09:00:00';
  const occurrenceId = `${id}@${created}|day:${day}`;
  const current = task({ id, title: 'Відкрита справа', created, repeat: 'daily', planDate: day, occurrenceId });
  const reopened = historyEvent(current, 'reopened', new Date(`${day}T11:00:00`).getTime(), occurrenceId, 12, day, '11:00');
  const staleCompletion = historyEvent(current, 'completed', new Date(`${day}T10:00:00`).getTime(), occurrenceId, 8, day, '10:00');
  useStore.setState({ tasks: [current], taskHistory: [reopened], nid: id + 1 });

  expect(mergeState({ tasks: [current], taskHistory: [staleCompletion] })).toBe(1);

  expect(completedTaskOccurrences(useStore.getState().taskHistory)).toEqual([]);
  expect(useStore.getState().tasks).toHaveLength(1);
});

test('past planner days render every saved completion snapshot instead of the renamed live task row', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-15T12:00:00+03:00') });
  await page.addInitScript(() => {
    const day = '2026-10-14';
    const created = '2026-10-13T08:00:00Z';
    const task = {
      id: 740, title: 'Перейменована справа', type: 'simple', done: false, someday: false,
      repeat: 'none', repeatDays: [], tags: [], created, planDate: day,
    };
    const history = ['09:00', '16:00'].map((time, index) => {
      const occurrenceId = `740@${created}|saved:${index}`;
      const at = new Date(`${day}T${time}:00+03:00`).getTime();
      return {
        id: `${occurrenceId}#${index}:${at}:completed`, occurrenceId,
        taskId: task.id, taskCreated: created, title: 'Стара назва', type: 'simple',
        kind: 'completed', sequence: index, at, date: day, time,
      };
    });
    localStorage.setItem('flow_v2', JSON.stringify({
      saveDate: new Date().toDateString(), weekTplSeeded: true, tasks: [task], taskHistory: history,
    }));
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'План', exact: true }).click();
  await page.getByRole('button', { name: /14 жовтня 2026/ }).click();

  const historyRows = page.locator('.plan-task-row[aria-label^="Історичне виконання:"]');
  await expect(historyRows).toHaveCount(2);
  await expect(historyRows.locator('.plan-task-nm')).toHaveText(['Стара назва', 'Стара назва']);
  await expect(page.getByRole('button', { name: 'Виконати: Перейменована справа', exact: true })).toHaveCount(0);
});

