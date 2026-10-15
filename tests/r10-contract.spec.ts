import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { buildForegroundNotifPayload } from '../src/bridge/notifPayload';
import { applyState, collectState } from '../src/services/persistence';
import { completedTaskOccurrences } from '../src/utils/taskHistory';
import { fmtDate } from '../src/utils/date';
import type { Task, Zone } from '../src/types';

const todayKey = () => fmtDate(new Date());
const localAt = (day: string, time: string) => new Date(`${day}T${time}:00`).getTime();

const task = (id: number, patch: Partial<Task> = {}): Task => ({
  id,
  title: `Задача ${id}`,
  type: 'simple',
  done: false,
  someday: false,
  created: '2026-09-01T09:00:00.000Z',
  repeat: 'none',
  repeatDays: Array(7).fill(false),
  tags: [],
  ...patch,
});

const zone = (id: number, nm: string, slots: Zone['slots'], prio = 1): Zone => ({
  id, nm, color: id === 1 ? '#3987e8' : '#d45c4a', slots, prio,
});

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
});

test('planned overlay keeps the assigned start keys and matches wall segments to absolute payload times', () => {
  const day = todayKey();
  useStore.setState({
    zones: [
      zone(1, 'Зона A', [{ s: '09:00', e: '12:00' }, { s: '14:00', e: '16:00' }]),
      zone(2, 'Зона B', []),
    ],
    planDayZones: { [day]: [{ zoneId: 2, s: '10:00', e: '11:00', ov: true }] },
    planDayOff: {},
  });

  const payload = buildForegroundNotifPayload();
  const wallDay = payload.questWallDays.find(item => item.date === day)!;
  const assigned = (zoneId: number, startMinute: number) => wallDay.assignedOccurrences.find(item =>
    item.zoneId === zoneId && item.startMinute === startMinute);
  const a09 = assigned(1, 9 * 60);
  const b10 = assigned(2, 10 * 60);
  const a14 = assigned(1, 14 * 60);
  expect(a09).toMatchObject({ zoneId: 1, startMinute: 540, endMinute: 720 });
  expect(b10).toMatchObject({ zoneId: 2, startMinute: 600, endMinute: 660 });
  expect(a14).toMatchObject({ zoneId: 1, startMinute: 840, endMinute: 960 });
  expect(wallDay.assignedOccurrences.filter(item => item.zoneId === 1)).toHaveLength(2);

  const a09Occurrence = payload.questZoneOccurrences.find(item => item.key === `${day}:1:09:00`);
  const b10Occurrence = payload.questZoneOccurrences.find(item => item.key === `${day}:2:10:00`);
  const a14Occurrence = payload.questZoneOccurrences.find(item => item.key === `${day}:1:14:00`);
  expect(a09Occurrence).toEqual({ zoneId: 1, startAt: localAt(day, '09:00'), endAt: localAt(day, '12:00'), key: `${day}:1:09:00` });
  expect(b10Occurrence).toEqual({ zoneId: 2, startAt: localAt(day, '10:00'), endAt: localAt(day, '11:00'), key: `${day}:2:10:00` });
  expect(a14Occurrence).toEqual({ zoneId: 1, startAt: localAt(day, '14:00'), endAt: localAt(day, '16:00'), key: `${day}:1:14:00` });

  const primaryAt = (time: string) => {
    const at = localAt(day, time);
    return payload.questTimeline.find(segment => segment.startAt <= at && at < segment.endAt)?.zoneId;
  };
  expect(primaryAt('09:30')).toBe(1);
  expect(primaryAt('10:30')).toBe(2);
  expect(primaryAt('11:30')).toBe(1);
  expect(primaryAt('14:30')).toBe(1);
});

test('legacy planner rows migrate to dated tasks while events and week prompts remain non-task items', () => {
  const day = todayKey();
  const legacy = {
    formatVersion: 2,
    tasks: [],
    nid: 500,
    planItems: {
      [day]: [
        { title: 'Зустріч із командою', done: false, time: '09:35' },
        { title: 'День народження', done: false, isEvent: true, time: '18:00' },
        { title: 'Заплануй наступний тиждень', done: false },
      ],
    },
  };

  expect(applyState(legacy, true)).toBe(true);
  const state = useStore.getState();
  const migrated = state.tasks.find(item => item.title === 'Зустріч із командою');
  expect(migrated).toMatchObject({ type: 'sched', planDate: day, schedDate: day, schedTime: '09:35' });
  expect(state.planItems[day].find(item => item.title === 'День народження')).toMatchObject({ isEvent: true, time: '18:00' });
  expect(state.planItems[day].find(item => item.title === 'Заплануй наступний тиждень')).toMatchObject({ isPrompt: true, done: false });
  expect(state.planItems[day]).toHaveLength(2);

  const promptIndex = state.planItems[day].findIndex(item => item.isPrompt);
  const xpBefore = state.xpTotal;
  useStore.getState().togglePlanItem(day, promptIndex);
  expect(useStore.getState().xpTotal).toBe(xpBefore);
  expect(useStore.getState().planItems[day][promptIndex].done).toBe(false);

  useStore.getState().toggleRestDay(day);
  useStore.getState().toggleRestDay(day);
  useStore.getState().toggleRestDay(day);
  expect(useStore.getState().planItems[day].filter(item => item.isPrompt)).toHaveLength(1);
  expect(useStore.getState().tasks.find(item => item.title === 'Зустріч із командою')?.planDate).toBe(day);
});

test('a delayed daily Done tap records yesterday once without changing today progress, priority, or XP twice', () => {
  const yesterday = new Date();
  yesterday.setHours(12, 0, 0, 0);
  yesterday.setDate(yesterday.getDate() - 1);
  const today = todayKey();
  const taskId = 811;
  const active = task(taskId, {
    title: 'Сьогоднішній чекліст',
    type: 'check',
    repeat: 'daily',
    planDate: today,
    done: false,
    counter: 17,
    counterTarget: 30,
    counterEnabled: true,
    counterCreditedProgress: 8,
    counterDay: today,
    items: [{ text: 'Вже зроблено', done: true, credited: true }, { text: 'Ще крок', done: false, credited: false }],
  });
  useStore.setState({ tasks: [active], priorities: [taskId, null, null], done: 5, xp: 23, xpTotal: 123, nid: taskId + 1 });
  const beforeTask = { ...useStore.getState().tasks[0], items: useStore.getState().tasks[0].items?.map(item => ({ ...item })) };
  const beforePriority = useStore.getState().priorities.slice();
  const completedAt = yesterday.getTime();

  expect(useStore.getState().recordPastTaskDone(taskId, completedAt)).toBe(true);
  const afterFirst = useStore.getState();
  expect(afterFirst.tasks[0]).toEqual(beforeTask);
  expect(afterFirst.priorities).toEqual(beforePriority);
  expect(completedTaskOccurrences(afterFirst.taskHistory).filter(event => event.date === fmtDate(yesterday))).toHaveLength(1);
  const credited = { done: afterFirst.done, xp: afterFirst.xp, xpTotal: afterFirst.xpTotal };

  expect(useStore.getState().recordPastTaskDone(taskId, completedAt)).toBe(false);
  const afterDuplicate = useStore.getState();
  expect(afterDuplicate.tasks[0]).toEqual(beforeTask);
  expect(afterDuplicate.priorities).toEqual(beforePriority);
  expect({ done: afterDuplicate.done, xp: afterDuplicate.xp, xpTotal: afterDuplicate.xpTotal }).toEqual(credited);
  expect(completedTaskOccurrences(afterDuplicate.taskHistory).filter(event => event.date === fmtDate(yesterday))).toHaveLength(1);
});

test('unsupported future or malformed format versions are rejected atomically', () => {
  useStore.setState({ tasks: [task(812)], notepad: 'Поточні дані' });
  const before = collectState();
  const futureVersion = { ...before, formatVersion: 99 };
  const malformedVersion = { ...before, formatVersion: 'two' };

  expect(applyState(futureVersion, true)).toBe(false);
  expect(collectState()).toEqual(before);
  expect(applyState(malformedVersion, true)).toBe(false);
  expect(collectState()).toEqual(before);
  expect(useStore.getState().notepad).toBe('Поточні дані');
});
