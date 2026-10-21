import { test, expect } from '@playwright/test';
import type { Task } from '../src/types';
import { getQuestTaskAvailability, buildQuestTimeline } from '../src/utils/questPolicy';
import { nextTaskReminderAt } from '../src/utils/taskSchedule';
import { useStore } from '../src/state/store';
import { applyState, collectState } from '../src/services/persistence';

const resetStore = () => useStore.setState(useStore.getInitialState(), true);
const requestedAt = new Date(2026, 9, 14, 10, 0).getTime();

function makeTask(id: number, patch: Partial<Task> = {}): Task {
  return {
    id,
    title: 'Нагадування',
    type: 'alarm',
    done: false,
    someday: false,
    zoneId: null,
    created: '2026-10-14T08:00:00',
    repeat: 'none',
    repeatDays: Array(7).fill(false),
    tags: [],
    alarmTime: '08:00',
    ...patch,
  };
}

test.beforeEach(resetStore);

test('a changed inline alarm time clears its deferral and resumes the edited reminder', () => {
  const now = new Date(2026, 9, 14, 10, 0);
  const original = makeTask(701);
  useStore.setState({ tasks: [original], zones: [] });
  useStore.getState().deferTask(original.id, 'not-today', requestedAt);

  useStore.getState().updateAlarm(original.id, '11:00');
  const changed = useStore.getState().tasks[0];
  const timeline = buildQuestTimeline({ zones: [], dayBlocks: () => [], now, days: 2 });

  expect(changed).toMatchObject({ alarmTime: '11:00' });
  expect(changed.questDeferral).toBeUndefined();
  expect(nextTaskReminderAt(changed, now, timeline)).toBe(new Date(2026, 9, 14, 11, 0).getTime());
  expect(getQuestTaskAvailability(changed, { now: new Date(2026, 9, 14, 11, 1), timeline, zones: [] }).eligible).toBe(true);
});

test('a normalized no-op alarm edit preserves the existing deferral and reminder', () => {
  const now = new Date(2026, 9, 14, 10, 0);
  const original = makeTask(702, { alarmTime: '11:00' });
  useStore.setState({ tasks: [original], zones: [] });
  useStore.getState().deferTask(original.id, 'not-today', requestedAt);
  const deferred = useStore.getState().tasks[0];

  useStore.getState().updateAlarm(original.id, '11:00');
  const unchanged = useStore.getState().tasks[0];
  const timeline = buildQuestTimeline({ zones: [], dayBlocks: () => [], now, days: 2 });

  expect(unchanged.questDeferral).toBe(deferred.questDeferral);
  expect(nextTaskReminderAt(unchanged, now, timeline)).toBe(deferred.questDeferral!.startAt);
});

function intervalTask(): Task {
  return makeTask(703, {
    type: 'simple',
    alarmTime: undefined,
    repeat: 'interval',
    repeatInterval: 30,
    repeatUnit: 'min',
    repeatMs: 30 * 60 * 1000,
  });
}

function saveInterval(id: number, repeatInterval: number, repeatUnit: NonNullable<Task['repeatUnit']>, note?: string): void {
  useStore.getState().saveTask(id, {
    title: 'Нагадування',
    type: 'simple',
    repeat: 'interval',
    repeatDays: Array(7).fill(false),
    repeatInterval,
    repeatUnit,
    note,
  });
}

test('an interval schedule edit clears deferral, while a note-only save preserves it', () => {
  const original = intervalTask();
  useStore.setState({ tasks: [original], zones: [] });
  useStore.getState().deferTask(original.id, 'not-today', requestedAt);
  const deferred = useStore.getState().tasks[0];

  saveInterval(original.id, 30, 'min', 'Моя нотатка');
  expect(useStore.getState().tasks[0].questDeferral).toBe(deferred.questDeferral);

  saveInterval(original.id, 1, 'hour', 'Моя нотатка');
  const changed = useStore.getState().tasks[0];
  expect(changed).toMatchObject({ repeat: 'interval', repeatInterval: 1, repeatUnit: 'hour', repeatMs: 60 * 60 * 1000 });
  expect(changed.questDeferral).toBeUndefined();
  const now = new Date(requestedAt);
  const timeline = buildQuestTimeline({ zones: [], dayBlocks: () => [], now, days: 2 });
  expect(getQuestTaskAvailability(changed, { now, timeline, zones: [] }).eligible).toBe(true);
});

test('changing interval units is treated as an explicit schedule edit even when duration matches', () => {
  const original = intervalTask();
  const oneHour: Task = { ...original, repeatInterval: 60, repeatUnit: 'min', repeatMs: 60 * 60 * 1000 };
  useStore.setState({ tasks: [oneHour], zones: [] });
  useStore.getState().deferTask(oneHour.id, 'not-today', requestedAt);

  saveInterval(oneHour.id, 1, 'hour');
  const changed = useStore.getState().tasks[0];
  expect(changed.repeatMs).toBe(oneHour.repeatMs);
  expect(changed.questDeferral).toBeUndefined();
});

test('Done archives a deferral across save and reload so Undo restores the pending occurrence', () => {
  const original = makeTask(704);
  useStore.setState({ tasks: [original], zones: [] });
  useStore.getState().deferTask(original.id, 'not-today', requestedAt);
  const deferral = useStore.getState().tasks[0].questDeferral;
  expect(deferral).toBeDefined();

  useStore.getState().setTaskDone(original.id, true, requestedAt + 60_000);
  const completed = useStore.getState().tasks[0];
  expect(completed.questDeferral).toBeUndefined();
  expect(completed.completionDeferral).toEqual(deferral);

  const saved = JSON.parse(JSON.stringify(collectState()));
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(saved, true)).toBe(true);
  expect(useStore.getState().tasks[0].completionDeferral).toEqual(deferral);

  useStore.getState().setTaskDone(original.id, false, requestedAt + 2 * 60_000);
  const undone = useStore.getState().tasks[0];
  expect(undone.done).toBe(false);
  expect(undone.questDeferral).toEqual(deferral);
  expect(undone.completionDeferral).toBeUndefined();
});

test('completion deferrals do not leak into clones, someday tasks, or a new repeat occurrence', () => {
  const daily = makeTask(705, { type: 'simple', repeat: 'daily', alarmTime: undefined });
  const archivedAlarm = makeTask(706);
  useStore.setState({ tasks: [daily, archivedAlarm], zones: [] });
  for (const item of [daily, archivedAlarm]) {
    useStore.getState().deferTask(item.id, 'not-today', requestedAt);
    useStore.getState().setTaskDone(item.id, true, requestedAt + 60_000);
  }
  expect(useStore.getState().tasks[0].completionDeferral).toBeDefined();
  expect(useStore.getState().tasks[1].completionDeferral).toBeDefined();

  useStore.getState().duplicateTask(daily.id);
  const copy = useStore.getState().tasks.find(item => item.id !== daily.id)!;
  expect(copy).toMatchObject({ questDeferral: undefined, completionDeferral: undefined });

  useStore.getState().toSomeday(archivedAlarm.id);
  const someday = useStore.getState().tasks.find(item => item.id === archivedAlarm.id)!;
  expect(someday).toMatchObject({ someday: true, questDeferral: undefined, completionDeferral: undefined });

  useStore.getState().resetRepeatingTasksFor(new Date(2026, 9, 15, 10, 0));
  const nextOccurrence = useStore.getState().tasks.find(item => item.id === daily.id)!;
  expect(nextOccurrence).toMatchObject({ done: false, questDeferral: undefined, completionDeferral: undefined });
});

test('explicit schedule edits clear archived deferrals, while note-only saves preserve them', () => {
  const alarm = makeTask(707, { alarmTime: '08:00' });
  const interval = intervalTask();
  interval.id = 708;
  useStore.setState({ tasks: [alarm, interval], zones: [] });
  for (const item of [alarm, interval]) {
    useStore.getState().deferTask(item.id, 'not-today', requestedAt);
    useStore.getState().setTaskDone(item.id, true, requestedAt + 60_000);
  }
  const archivedAlarm = useStore.getState().tasks.find(item => item.id === alarm.id)!;
  const archivedInterval = useStore.getState().tasks.find(item => item.id === interval.id)!;
  expect(archivedAlarm.completionDeferral).toBeDefined();
  expect(archivedInterval.completionDeferral).toBeDefined();

  useStore.getState().updateAlarm(alarm.id, '08:00');
  saveInterval(interval.id, 30, 'min', 'Нотатка без зміни розкладу');
  expect(useStore.getState().tasks.find(item => item.id === alarm.id)?.completionDeferral).toBe(archivedAlarm.completionDeferral);
  expect(useStore.getState().tasks.find(item => item.id === interval.id)?.completionDeferral).toBe(archivedInterval.completionDeferral);

  useStore.getState().updateAlarm(alarm.id, '09:00');
  saveInterval(interval.id, 1, 'hour', 'Змінена нотатка');
  expect(useStore.getState().tasks.find(item => item.id === alarm.id)).toMatchObject({ questDeferral: undefined, completionDeferral: undefined });
  expect(useStore.getState().tasks.find(item => item.id === interval.id)).toMatchObject({ questDeferral: undefined, completionDeferral: undefined });

  const noOpAlarm = makeTask(709, { alarmTime: '11:00' });
  useStore.setState({ tasks: [noOpAlarm], zones: [] });
  useStore.getState().deferTask(noOpAlarm.id, 'not-today', requestedAt);
  const savedAlarm = useStore.getState().tasks[0];
  useStore.getState().updateAlarm(noOpAlarm.id, '11:00');
  expect(useStore.getState().tasks[0].questDeferral).toBe(savedAlarm.questDeferral);
});
