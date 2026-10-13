import { test, expect } from '@playwright/test';
import type { Task, TaskType } from '../src/types';
import { useStore } from '../src/state/store';
import { applyState, collectState } from '../src/services/persistence';
import { recurringReminderRows } from '../src/bridge/notifPayload';
import { isTaskInTimeWindow, nextTaskReminderAt, taskDoneOn, taskOccursOn } from '../src/utils/taskSchedule';

const note = 'Деталі задачі українською 🌿\nДругий рядок залишається після зміни типу.';
const day = '2030-10-09';
const types: TaskType[] = ['simple', 'check', 'counter', 'note', 'alarm', 'sched', 'timewin', 'ctx', 'negative', 'zonelinked'];

function task(id: number): Task {
  const result = useStore.getState().tasks.find(candidate => candidate.id === id);
  expect(result, `Task ${id} must retain its identity`).toBeDefined();
  return result!;
}

function reloadStore() {
  const json = JSON.stringify(collectState());
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(JSON.parse(json), true)).toBe(true);
}

test.beforeEach(() => useStore.setState(useStore.getInitialState(), true));
test.afterEach(() => useStore.setState(useStore.getInitialState(), true));

for (const type of types) {
  test(`${type}: create, act, restore JSON, edit and convert without stale scheduling`, () => {
    const zone = useStore.getState().zones.find(candidate => candidate.id !== 0)!;
    const typeFields: Partial<Record<TaskType, Partial<Task>>> = {
      alarm: { alarmTime: '09:15', reminderEnabled: true },
      sched: { schedDate: day, schedTime: '09:30', reminderEnabled: true, remindBeforeMinutes: 15 },
      timewin: { windowStart: '22:00', windowEnd: '06:00' },
      counter: { counterTarget: 2, counterStep: 1, counterUnit: 'count' as const },
      negative: { negXp: 7 },
      zonelinked: { zoneId: zone.id },
    };
    const fields = typeFields[type] || {};
    const id = useStore.getState().saveTask(null, {
      title: `Тип ${type}`, type, note, planDate: day, repeat: 'none', tags: ['перевірка'], ...fields,
    });
    expect(task(id)).toMatchObject({ id, title: `Тип ${type}`, type, note, planDate: day, ...fields });

    if (type === 'check') {
      useStore.getState().addChecklistItem(id, '  Перший крок  ');
      useStore.getState().addChecklistItem(id, 'Другий крок');
      useStore.getState().toggleChecklistItem(id, 0);
      expect(task(id).items).toEqual([
        { text: 'Перший крок', done: true, credited: true }, { text: 'Другий крок', done: false },
      ]);
      expect(task(id).done).toBe(false);
    }
    if (type === 'counter') {
      useStore.getState().changeCounter(id, 1);
      useStore.getState().changeCounter(id, 1);
      expect(task(id)).toMatchObject({ counter: 2, counterCreditedProgress: 2, cntHit: true, done: false });
    }
    if (type === 'ctx') {
      useStore.getState().toggleCtxTag(id, 'вдома');
      expect(task(id).ctxTags).toEqual(['вдома']);
    }
    if (type === 'negative') {
      useStore.getState().toggleTask(id);
      expect(task(id)).toMatchObject({ counter: 1, done: false, negXp: 7 });
      expect(useStore.getState().negCount).toBe(1);
      useStore.getState().setTaskDone(id, true);
      expect(task(id).done).toBe(false);
    }
    if (type === 'timewin') {
      expect(isTaskInTimeWindow(task(id), new Date(day + 'T23:00:00'))).toBe(true);
      expect(isTaskInTimeWindow(task(id), new Date(day + 'T05:00:00'))).toBe(true);
      expect(isTaskInTimeWindow(task(id), new Date(day + 'T12:00:00'))).toBe(false);
    }
    if (type === 'zonelinked') expect(task(id)).toMatchObject({ zoneId: zone.id, zoneName: zone.nm, zoneColor: zone.color });
    if (type === 'alarm' || type === 'sched') {
      const time = type === 'alarm' ? '09:15' : '09:30';
      expect(nextTaskReminderAt(task(id), new Date(day + 'T08:00:00'))).toBe(new Date(`${day}T${time}:00`).getTime());
      useStore.getState().markScheduledFired(id);
      useStore.getState().markPreReminderFired(id);
    } else {
      expect(nextTaskReminderAt(task(id), new Date(day + 'T08:00:00'))).toBeUndefined();
    }

    const beforeReload = JSON.parse(JSON.stringify(task(id)));
    reloadStore();
    expect(task(id)).toMatchObject(beforeReload);
    const editedNote = note + '\nУточнення після відновлення.';
    useStore.getState().saveTask(id, { ...task(id), title: `Змінений ${type}`, note: editedNote });
    reloadStore();
    expect(task(id)).toMatchObject({ id, type, title: `Змінений ${type}`, note: editedNote, ...fields });

    if (type !== 'negative') {
      const doneBefore = useStore.getState().done;
      useStore.getState().setTaskDone(id, true);
      expect(task(id).done).toBe(true);
      if (type === 'timewin') expect(task(id).completedToday).toBe(true);
      if (type === 'zonelinked') expect(task(id).zoneDoneToday).toBe(true);
      useStore.getState().setTaskDone(id, true);
      expect(useStore.getState().done).toBe(doneBefore + 1);
      useStore.getState().setTaskDone(id, false);
    }

    useStore.getState().saveTask(id, {
      title: `Тепер проста: ${type}`, type: 'simple', note: editedNote,
      repeat: 'none', planDate: undefined, zoneId: null,
    });
    reloadStore();
    const converted = task(id);
    expect(converted).toMatchObject({ id, type: 'simple', note: editedNote, done: false, zoneId: null, repeat: 'none' });
    for (const field of ['planDate', 'schedDate', 'schedTime', 'alarmTime', 'windowStart', 'windowEnd', 'completedToday', 'zoneDoneToday', 'zoneOccurrenceKey', 'repeatMs', 'repeatInterval', 'repeatUnit'] as const) {
      expect(converted[field], `${type} must not retain active ${field}`).toBeUndefined();
    }
    expect(converted.alarmFired).toBeFalsy();
    expect(converted.firedSched).toBeFalsy();
    expect(converted.firedPre).toBeFalsy();
    expect(converted.counterEnabled).toBe(false);
    expect(nextTaskReminderAt(converted, new Date(day + 'T08:00:00'))).toBeUndefined();
  });
}

test('a daily scheduled task occurs on future calendar days without appearing before its start', () => {
  const id = useStore.getState().saveTask(null, {
    title: 'Щодня о 09:30', type: 'sched', schedDate: day, schedTime: '09:30', repeat: 'daily',
  });
  expect(taskOccursOn(task(id), '2030-10-08')).toBe(false);
  expect(taskOccursOn(task(id), day)).toBe(true);
  expect(taskOccursOn(task(id), '2030-10-10')).toBe(true);
  expect(taskOccursOn(task(id), '2030-11-01')).toBe(true);
  expect(taskOccursOn({ ...task(id), trashed: true }, '2030-10-10')).toBe(false);
  expect(taskOccursOn({ ...task(id), someday: true }, '2030-10-10')).toBe(false);
});

test('completed daily schedules leave tomorrow incomplete and retain tomorrow native reminders', () => {
  const id = useStore.getState().saveTask(null, {
    title: 'Щоденний прийом', type: 'sched', schedDate: day, schedTime: '09:30',
    repeat: 'daily', reminderEnabled: true, remindBeforeMinutes: 15,
  });
  const completed = { ...task(id), done: true, doneDate: day, firedSched: true, firedPre: true };
  expect(taskDoneOn(completed, day)).toBe(true);
  expect(taskOccursOn(completed, '2030-10-10')).toBe(true);
  expect(taskDoneOn(completed, '2030-10-10')).toBe(false);
  const rows = recurringReminderRows(completed, new Date(day + 'T12:00:00'));
  expect(rows).toHaveLength(2);
  expect(rows[0]).toMatchObject({
    id, repeat: 'daily', anchorDate: '2030-10-10', localTime: '09:30', fired: false,
    dueMs: new Date('2030-10-10T09:30:00').getTime(), advanceMinutes: 0,
  });
  expect(rows[1]).toMatchObject({
    id: id + '_pre', repeat: 'daily', anchorDate: '2030-10-10', localTime: '09:30', fired: false,
    dueMs: new Date('2030-10-10T09:15:00').getTime(), advanceMinutes: 15,
  });
});

test('custom reminders honor their first eligible day and attach an overnight advance reminder to that occurrence', () => {
  const repeatDays = [false, false, false, false, true, false, false]; // Fridays, Monday first.
  const id = useStore.getState().saveTask(null, {
    title: 'Щоп’ятниці', type: 'sched', schedDate: '2030-10-10', schedTime: '00:30',
    repeat: 'custom', repeatDays, reminderEnabled: true, remindBeforeMinutes: 90,
  });
  expect(taskOccursOn(task(id), '2030-10-04')).toBe(false); // Friday before the anchor.
  expect(taskOccursOn(task(id), '2030-10-10')).toBe(false); // Thursday anchor is not selected.
  expect(taskOccursOn(task(id), '2030-10-11')).toBe(true);
  expect(taskOccursOn(task(id), '2030-10-12')).toBe(false);
  const rows = recurringReminderRows(task(id), new Date('2030-10-08T12:00:00'));
  expect(rows).toHaveLength(2);
  expect(rows[0]).toMatchObject({
    id, repeat: 'custom', repeatDays, anchorDate: '2030-10-11', localTime: '00:30',
    dueMs: new Date('2030-10-11T00:30:00').getTime(), advanceMinutes: 0,
  });
  expect(rows[1]).toMatchObject({
    id: id + '_pre', repeat: 'custom', repeatDays, anchorDate: '2030-10-11', localTime: '00:30',
    dueMs: new Date('2030-10-10T23:00:00').getTime(), advanceMinutes: 90,
  });
  expect(recurringReminderRows({ ...task(id), reminderEnabled: false }, new Date('2030-10-08T12:00:00'))).toEqual([]);
});
