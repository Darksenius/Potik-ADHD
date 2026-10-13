import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { checkReminders } from '../src/services/reminders';
import { isTaskAvailable, nextTaskReminderAt } from '../src/utils/taskSchedule';
import { fmtDate } from '../src/utils/date';
import type { Task } from '../src/types';

const task = (id: number, patch: Partial<Task> = {}): Task => ({
  id, title: 'Повторювана справа', type: 'simple', done: false, someday: false,
  created: '2026-10-08T09:00:00', repeat: 'none', repeatDays: Array(7).fill(false), tags: [], ...patch,
});
const thursday = new Date('2026-10-08T12:00:00');
const friday = new Date('2026-10-09T12:00:00');
const saturday = new Date('2026-10-10T12:00:00');
const monday = new Date('2026-10-12T12:00:00');

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  (globalThis as any).window = {};
});
test.afterEach(() => { delete (globalThis as any).window; });

test('a one-off alarm stays fired across midnight until its date or time is edited', () => {
  useStore.setState({ tasks: [task(499, { type: 'alarm', alarmTime: '12:00', alarmFired: true, planDate: '2026-10-08' })] });
  useStore.getState().dailyResetIfNeeded('Wed Oct 07 2026');
  useStore.getState().resetRepeatingTasksFor(friday);
  expect(nextTaskReminderAt(useStore.getState().tasks[0], friday)).toBeUndefined();
  useStore.getState().saveTask(499, { title: 'Новий день', type: 'alarm', alarmTime: '12:00', planDate: '2026-10-09' });
  expect(useStore.getState().tasks[0].alarmFired).toBe(false);
  expect(nextTaskReminderAt(useStore.getState().tasks[0], new Date('2026-10-09T11:00:00'))).toBe(new Date('2026-10-09T12:00:00').getTime());
});

test('live interval reset credits both occurrences once and uses the same progress reset as startup', () => {
  const id = useStore.getState().saveTask(null, {
    title: 'Повтор через хвилину', type: 'check', repeat: 'interval', repeatInterval: 1, repeatUnit: 'min',
    planDate: fmtDate(new Date()), counterEnabled: true, counterTarget: 2,
  });
  useStore.getState().addChecklistItem(id, 'Крок');
  useStore.getState().toggleChecklistItem(id, 0);
  useStore.getState().changeCounter(id, 1);
  useStore.getState().setTaskDone(id, true);
  const firstXp = useStore.getState().xpTotal;
  useStore.setState(state => ({ tasks: state.tasks.map(t => ({ ...t, nextRepeatAt: Date.now() - 1 })) }));
  checkReminders();
  expect(useStore.getState().tasks[0]).toMatchObject({
    done: false, completionCredited: false, nextRepeatAt: 0, counter: 0,
    counterCreditedProgress: 0, items: [{ text: 'Крок', done: false, credited: false }],
  });
  useStore.getState().setTaskDone(id, true);
  useStore.getState().setTaskDone(id, false);
  useStore.getState().setTaskDone(id, true);
  expect(useStore.getState().done).toBe(2);
  expect(useStore.getState().xpTotal).toBe(firstXp + 10);
});

test('time windows honor none, custom days and elapsed intervals rather than resetting every midnight', () => {
  const future = Date.now() + 7 * 86400000;
  useStore.setState({ tasks: [
    task(1, { type: 'timewin', done: true, completedToday: true, doneDate: '2026-10-07' }),
    task(2, { type: 'timewin', done: true, completedToday: true, doneDate: '2026-10-07', repeat: 'interval', repeatMs: 7 * 86400000, nextRepeatAt: future }),
    task(3, { type: 'timewin', done: true, completedToday: true, doneDate: '2026-10-08', repeat: 'custom', repeatDays: [true, false, false, true, false, false, false], planDate: '2026-10-08' }),
  ] });
  useStore.getState().resetRepeatingTasksFor(friday);
  expect(useStore.getState().tasks.map(t => t.done)).toEqual([true, true, true]);
  useStore.getState().resetRepeatingTasksFor(monday);
  expect(useStore.getState().tasks[2]).toMatchObject({ done: false, completedToday: false, planDate: '2026-10-12' });
  useStore.getState().dailyResetIfNeeded('Wed Sep 30 2026');
  expect(useStore.getState().tasks[0].done).toBe(true);
  expect(useStore.getState().tasks[1]).toMatchObject({ done: true, nextRepeatAt: future });
  useStore.getState().resetRepeatingTasksFor(new Date(future));
  expect(useStore.getState().tasks[1]).toMatchObject({ done: false, completedToday: false, nextRepeatAt: 0 });
});

test('unfinished daily and weekday counters/checklists reset only on the next eligible day and once per day', () => {
  useStore.setState({ tasks: [
    task(1, { type: 'simple', counterEnabled: true, counter: 750, counterCreditedProgress: 750, counterDay: '2026-10-08', repeat: 'daily', planDate: '2026-10-08', items: [{ text: 'Вода', done: true, credited: true }] }),
    task(2, { type: 'counter', counter: 4, counterCreditedProgress: 4, counterDay: '2026-10-09', repeat: 'weekdays', planDate: '2026-10-09' }),
  ] });
  useStore.getState().resetRepeatingTasksFor(friday);
  expect(useStore.getState().tasks[0]).toMatchObject({ counter: 0, counterCreditedProgress: 0, counterDay: '2026-10-09', items: [{ done: false, credited: false }] });
  expect(useStore.getState().tasks[1].counter).toBe(4);
  useStore.setState(state => ({ tasks: state.tasks.map(t => t.id === 1 ? { ...t, counter: 250 } : t) }));
  useStore.getState().resetRepeatingTasksFor(friday);
  expect(useStore.getState().tasks[0].counter).toBe(250);
  useStore.getState().resetRepeatingTasksFor(saturday);
  expect(useStore.getState().tasks[1].counter).toBe(4);
  useStore.getState().resetRepeatingTasksFor(monday);
  expect(useStore.getState().tasks[1]).toMatchObject({ counter: 0, counterDay: '2026-10-12' });
});

test('recurring scheduled and window tasks keep their first eligible date and advance incomplete occurrences together', () => {
  useStore.setState({ tasks: [
    task(1, { type: 'sched', repeat: 'daily', planDate: '2026-10-09', schedDate: '2026-10-09', schedTime: '14:00', firedSched: true }),
    task(2, { type: 'timewin', repeat: 'daily', planDate: '2026-10-09', windowStart: '10:00', windowEnd: '16:00' }),
  ] });
  useStore.getState().resetRepeatingTasksFor(thursday);
  expect(useStore.getState().tasks.map(t => isTaskAvailable(t, thursday))).toEqual([false, false]);
  useStore.getState().resetRepeatingTasksFor(monday);
  expect(useStore.getState().tasks[0]).toMatchObject({ planDate: '2026-10-12', schedDate: '2026-10-12', firedSched: false });
  expect(useStore.getState().tasks[1].planDate).toBe('2026-10-12');
  expect(nextTaskReminderAt(useStore.getState().tasks[0], monday)).toBe(new Date('2026-10-12T14:00:00').getTime());
  expect(useStore.getState().tasks.map(t => isTaskAvailable(t, monday))).toEqual([true, true]);
});

test('everyzone resets at the next assigned slot, persists its identity across reload and ignores other overlapping zones', () => {
  useStore.setState({ zones: [
    { id: 80, nm: 'Робота', color: '#fff', prio: 1, slots: [{ s: '09:00', e: '12:00' }, { s: '14:00', e: '18:00' }] },
    { id: 81, nm: 'Інше', color: '#eee', prio: 20, slots: [{ s: '10:00', e: '11:00' }] },
  ], tasks: [task(1, { type: 'zonelinked', repeat: 'everyzone', zoneId: 80, counterEnabled: true })] });
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T09:30:00'));
  const key = useStore.getState().tasks[0].zoneOccurrenceKey;
  useStore.setState(state => ({ tasks: state.tasks.map(t => ({ ...t, done: true, zoneDoneToday: true, completionCredited: true, counter: 3 })) }));
  const saved = JSON.parse(JSON.stringify(useStore.getState().tasks));
  useStore.setState({ tasks: saved });
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T10:30:00'));
  expect(useStore.getState().tasks[0]).toMatchObject({ done: true, zoneOccurrenceKey: key, counter: 3 });
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T13:00:00'));
  expect(useStore.getState().tasks[0].done).toBe(true);
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T14:00:00'));
  expect(useStore.getState().tasks[0]).toMatchObject({ done: false, zoneDoneToday: false, completionCredited: false, counter: 0, zoneOccurrenceKey: '2026-10-08:80:14:00' });
});

test('everyzone merges continuous slots and uses the actual planned override start', () => {
  useStore.setState({ zones: [{ id: 80, nm: 'Робота', color: '#fff', prio: 1, slots: [{ s: '09:00', e: '12:00' }, { s: '11:00', e: '13:00' }] }], tasks: [task(1, { type: 'zonelinked', repeat: 'everyzone', zoneId: 80 })] });
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T10:00:00'));
  useStore.setState(state => ({ tasks: state.tasks.map(t => ({ ...t, done: true })) }));
  useStore.getState().checkZoneRepeats(new Date('2026-10-08T12:30:00'));
  expect(useStore.getState().tasks[0].done).toBe(true);
  useStore.setState({ planDayZones: { '2026-10-09': [{ zoneId: 80, s: '15:00', e: '17:00' }] } });
  useStore.getState().checkZoneRepeats(new Date('2026-10-09T15:00:00'));
  expect(useStore.getState().tasks[0]).toMatchObject({ done: false, zoneOccurrenceKey: '2026-10-09:80:15:00' });
});

test('negative daily counts reset yesterday but preserve a click already recorded today', () => {
  const today = fmtDate(new Date());
  useStore.setState({ tasks: [
    task(1, { type: 'negative', counter: 4, counterDay: '2026-09-30' }),
    task(2, { type: 'negative', counter: 1, counterDay: today }),
  ] });
  useStore.getState().dailyResetIfNeeded('Wed Sep 30 2026');
  expect(useStore.getState().tasks[0]).toMatchObject({ counter: 0, counterDay: today });
  expect(useStore.getState().tasks[1].counter).toBe(1);
  useStore.getState().checkDailyReset();
  expect(useStore.getState().tasks[1].counter).toBe(1);
});
