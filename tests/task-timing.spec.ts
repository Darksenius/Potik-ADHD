import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { nextTaskReminderAt, isTaskAvailable } from '../src/utils/taskSchedule';
import type { Task } from '../src/types';
import { handleNativeEvent } from '../src/bridge/nativeBridge';

const task = (type: Task['type']): Task => ({ id: 41, title: 'Перенести', type, done: false, someday: false, repeat: 'none', repeatDays: [], tags: [], created: '2026-10-08T09:00:00.000Z', planDate: '2026-10-08', schedDate: '2026-10-08', schedTime: '23:50', alarmTime: '23:50' });
const originalNow = Date.now;
test.beforeEach(() => useStore.setState(useStore.getInitialState(), true));
test.afterEach(() => { Date.now = originalNow; });

test('a newly created task cannot inherit an older queued command with a reused id', () => {
  const fresh = task('simple');
  fresh.created = new Date().toISOString();
  fresh.counterEnabled = true; fresh.counter = 0;
  useStore.setState({ tasks: [fresh] });
  const earlier = new Date(fresh.created).getTime() - 1000;
  for (const event of ['task_done:41', 'task_skip:41', 'task_inc:41']) expect(handleNativeEvent(event, earlier)).toBe(false);
  expect(useStore.getState().tasks[0]).toMatchObject({ done: false, counter: 0 });
  expect(useStore.getState().tasks[0].snoozeUntil).toBeUndefined();
});

test('a queued legacy skip chooses the next zone from the tap, not a delayed reopening', () => {
  const tap = new Date('2026-10-08T18:10:00').getTime();
  useStore.setState({ tasks: [task('sched')] });
  expect(handleNativeEvent('task_skip:41', tap)).toBe(true);
  const deferred = useStore.getState().tasks[0];
  expect(deferred.questDeferral).toMatchObject({ kind: 'next-zone', requestedAt: tap, sourceZoneId: 5, targetZoneId: 1,
    startAt: new Date('2026-10-08T22:00:00').getTime(), endAt: new Date('2026-10-09T06:00:00').getTime() });
  expect(deferred.snoozeUntil).toBe(0);
});

test('numeric snooze across midnight preserves an eligible reminder with seconds in the clock', () => {
  const start = new Date('2026-10-08T23:50:30.500').getTime();
  Date.now = () => start;
  for (const type of ['sched', 'alarm'] as const) {
    useStore.setState({ tasks: [task(type)] });
    useStore.getState().snoozeTask(41, 15);
    const t = useStore.getState().tasks[0];
    const due = nextTaskReminderAt(t, new Date(start));
    expect(due).toBe(new Date('2026-10-09T00:06:00').getTime());
    expect(t.planDate).toBe('2026-10-09');
    expect(t.snoozeUntil).toBe(due);
    expect(isTaskAvailable(t, new Date(due! - 1))).toBe(false);
    expect(isTaskAvailable(t, new Date(due!))).toBe(true);
  }
});

test('tomorrow keeps the task time and editing a simple day clears its obsolete snooze', () => {
  Date.now = () => new Date('2026-10-08T22:22:30').getTime();
  useStore.setState({ tasks: [task('sched')] });
  useStore.getState().snoozeTask(41, 'tomorrow');
  expect(useStore.getState().tasks[0]).toMatchObject({ schedDate: '2026-10-09', planDate: '2026-10-09', schedTime: '23:50', snoozeUntil: 0 });
  useStore.getState().saveTask(41, { title: 'Без часу', type: 'simple', planDate: '2026-10-09' });
  expect(useStore.getState().tasks[0].schedTime).toBeUndefined();
  useStore.getState().snoozeTask(41, 180);
  useStore.getState().saveTask(41, { title: 'Без часу', type: 'simple', planDate: '2026-10-10' });
  expect(useStore.getState().tasks[0].snoozeUntil).toBe(0);
});
