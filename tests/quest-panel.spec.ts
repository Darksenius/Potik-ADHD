import { test, expect } from '@playwright/test';
import type { Zone } from '../src/types';
import { buildQuestTimeline, createQuestDeferral, getQuestTaskAvailability, selectQuestTasks, type QuestContext } from '../src/utils/questPolicy';
import { nextTaskReminderAt, taskPreReminderAt } from '../src/utils/taskSchedule';
import { recurringReminderRows } from '../src/bridge/notifPayload';
import type { Task } from '../src/types';
import { useStore } from '../src/state/store';

const task = (id: number, patch: Partial<Task> = {}): Task => ({
  id, title: `Задача ${id}`, type: 'simple', done: false, someday: false,
  created: '2026-10-14T08:00:00', repeat: 'none', repeatDays: Array(7).fill(false), tags: [],
  ...patch,
});

const contextAt = (now: Date, zones: Zone[], days = 3): QuestContext => ({
  now,
  timeline: buildQuestTimeline({ zones, dayBlocks: () => [], now, days }),
  zones,
  priorities: [],
});

test('quest ranking filters by the current primary zone before applying priority', () => {
  const now = new Date('2026-10-14T10:30:00');
  const zones: Zone[] = [
    { id: 1, nm: 'Робота', color: '#0f0', prio: 1, slots: [{ s: '09:00', e: '12:00' }] },
    { id: 2, nm: 'Дім', color: '#00f', prio: 1, slots: [{ s: '12:00', e: '15:00' }] },
  ];
  const ctx = { ...contextAt(now, zones), priorities: [2, 3, 4] };
  const items = [
    task(1, { title: 'Пізніша справа', zoneId: 1 }),
    task(2, { title: 'Pinned in another zone', zoneId: 2 }),
    task(3, { title: 'Швидкий чекліст', type: 'check' }),
    task(4, { title: 'Щоденник', type: 'negative' }),
  ];
  expect(selectQuestTasks(items, ctx, 5).map(item => item.id)).toEqual([3, 1]);
  expect(getQuestTaskAvailability(items[1], ctx)).toMatchObject({ eligible: false, reason: 'wrong-zone' });
  expect(getQuestTaskAvailability(items[3], ctx)).toMatchObject({ eligible: false, reason: 'journal' });
});

test('an unused saved day template does not deactivate its baseline zone after recalculation', () => {
  const zone: Zone = { id: 44, nm: 'Вечір', color: '#a0f', prio: 1, active: true, slots: [{ s: '18:00', e: '22:00' }] };
  useStore.setState({
    zones: [zone],
    dayTemplates: [{ id: 'unused-evening', name: 'Вечірній шаблон', blocks: [{ zoneId: zone.id, s: '18:00', e: '22:00' }] }],
    planDayZones: {},
    planRules: [],
  });
  useStore.getState().recalculateZoneUsage();
  const recalculatedZone = useStore.getState().zones[0];
  expect(recalculatedZone).toMatchObject({ id: zone.id, active: true, bound: false });

  const now = new Date('2026-10-14T19:00:00');
  const timeline = buildQuestTimeline({ zones: [recalculatedZone], dayBlocks: () => [], now, days: 1 });
  expect(getQuestTaskAvailability(task(45, { zoneId: zone.id }), { now, timeline, zones: [recalculatedZone] }).eligible).toBe(true);
});

test('next-zone deferral overrides a future schedule only in the target and fresh source zones', () => {
  const zones: Zone[] = [
    { id: 1, nm: 'Робота', color: '#0f0', prio: 1, slots: [{ s: '09:00', e: '11:00' }, { s: '14:00', e: '16:00' }] },
    { id: 2, nm: 'Дім', color: '#00f', prio: 1, slots: [{ s: '11:00', e: '13:00' }] },
  ];
  const requestedAt = new Date('2026-10-14T10:30:00').getTime();
  const beforeTarget = contextAt(new Date(requestedAt), zones, 3);
  const original = task(10, { type: 'sched', zoneId: 1, schedDate: '2026-10-15', planDate: '2026-10-15', schedTime: '21:00' });
  const deferral = createQuestDeferral(original, 'next-zone', { requestedAt, timeline: beforeTarget.timeline, zones });
  const deferred = { ...original, questDeferral: deferral };
  expect(deferral).toMatchObject({ kind: 'next-zone', sourceZoneId: 1, targetZoneId: 2 });

  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-14T10:45:00') }).eligible).toBe(false);
  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-14T11:30:00') }).eligible).toBe(true);
  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-14T13:30:00') }).eligible).toBe(false);
  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-14T14:30:00') }).eligible).toBe(true);
  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-15T14:30:00') }).eligible).toBe(true);
  expect(getQuestTaskAvailability(deferred, { ...beforeTarget, now: new Date('2026-10-14T12:30:00') }).eligible).toBe(true);
});

test('not-today deferral uses local midnight for eligibility and the target zone for linked tasks', () => {
  const zones: Zone[] = [
    { id: 7, nm: 'Навчання', color: '#a0f', prio: 1, slots: [{ s: '09:00', e: '11:00' }] },
  ];
  const requestedAt = new Date('2026-10-14T10:15:00').getTime();
  const ctx = contextAt(new Date(requestedAt), zones, 4);
  const scheduled = task(20, {
    type: 'sched', zoneId: 7, schedDate: '2026-10-20', planDate: '2026-10-20', schedTime: '20:00',
    windowStart: '18:00', windowEnd: '19:00',
  });
  const deferral = createQuestDeferral(scheduled, 'not-today', { requestedAt, timeline: ctx.timeline, zones });
  expect(deferral.startAt).toBe(new Date('2026-10-15T00:00:00').getTime());
  const deferred = { ...scheduled, questDeferral: deferral };
  expect(getQuestTaskAvailability(deferred, { ...ctx, now: new Date('2026-10-14T23:59:00') }).eligible).toBe(false);
  expect(getQuestTaskAvailability(deferred, { ...ctx, now: new Date('2026-10-15T09:30:00') }).eligible).toBe(true);
  expect(getQuestTaskAvailability(deferred, { ...ctx, now: new Date('2026-10-16T09:30:00') }).eligible).toBe(true);

  const unbound = task(21, { type: 'sched', schedDate: '2026-10-20', planDate: '2026-10-20', schedTime: '20:00' });
  const openDeferral = createQuestDeferral(unbound, 'not-today', { requestedAt, timeline: ctx.timeline, zones });
  expect(openDeferral.startAt).toBe(requestedAt + 23 * 60 * 60 * 1000);
  expect(getQuestTaskAvailability({ ...unbound, questDeferral: openDeferral }, { ...ctx, now: new Date(openDeferral.startAt - 1) }).eligible).toBe(false);
  expect(getQuestTaskAvailability({ ...unbound, questDeferral: openDeferral }, { ...ctx, now: new Date(openDeferral.startAt) }).eligible).toBe(true);
});

test('next-zone after the last named zone follows not-today instead of targeting free time', () => {
  const zones: Zone[] = [{ id: 7, nm: 'Вечір', color: '#a0f', prio: 1, slots: [{ s: '18:00', e: '22:00' }] }];
  const requestedAt = new Date('2026-10-14T21:30:00').getTime();
  const ctx = contextAt(new Date(requestedAt), zones, 2);
  const linked = task(27, { zoneId: 7 });
  const deferred = createQuestDeferral(linked, 'next-zone', { requestedAt, timeline: ctx.timeline, zones });
  expect(deferred).toEqual({ kind: 'not-today', requestedAt, sourceZoneId: 7, targetZoneId: 7,
    startAt: new Date('2026-10-15T00:00:00').getTime() });
  expect(getQuestTaskAvailability({ ...linked, questDeferral: deferred }, { ...ctx, now: new Date('2026-10-14T22:30:00') }).eligible).toBe(false);
  expect(getQuestTaskAvailability({ ...linked, questDeferral: deferred }, { ...ctx, now: new Date('2026-10-15T18:30:00') }).eligible).toBe(true);
  const unbound = createQuestDeferral(task(28), 'next-zone', { requestedAt, timeline: ctx.timeline, zones });
  expect(unbound).toEqual({ kind: 'not-today', requestedAt, sourceZoneId: 7, targetZoneId: 0,
    startAt: requestedAt + 23 * 60 * 60 * 1000 });
});

test('next-zone skips a free gap and waits for the next named zone', () => {
  const zones: Zone[] = [
    { id: 1, nm: 'Ранок', color: '#0f0', prio: 1, slots: [{ s: '08:00', e: '10:00' }] },
    { id: 2, nm: 'Робота', color: '#00f', prio: 1, slots: [{ s: '11:00', e: '12:00' }] },
  ];
  const requestedAt = new Date('2026-10-14T09:30:00').getTime();
  const ctx = contextAt(new Date(requestedAt), zones);
  const original = task(29, { zoneId: 1 });
  const deferral = createQuestDeferral(original, 'next-zone', { requestedAt, timeline: ctx.timeline, zones });
  expect(deferral).toMatchObject({ kind: 'next-zone', sourceZoneId: 1, targetZoneId: 2,
    startAt: new Date('2026-10-14T11:00:00').getTime() });
  expect(getQuestTaskAvailability({ ...original, questDeferral: deferral }, { ...ctx, now: new Date('2026-10-14T10:30:00') }).eligible).toBe(false);
  expect(getQuestTaskAvailability({ ...original, questDeferral: deferral }, { ...ctx, now: new Date('2026-10-14T11:30:00') }).eligible).toBe(true);
});

test('a scheduled deferral has one reminder at the first own-zone start and no old pre-reminder', () => {
  const zones: Zone[] = [
    { id: 9, nm: 'Робота', color: '#0f0', prio: 1, slots: [{ s: '10:00', e: '13:00' }] },
  ];
  const now = new Date('2026-10-14T12:00:00');
  const timeline = contextAt(now, zones, 3).timeline;
  const requestedAt = new Date('2026-10-14T12:00:00').getTime();
  const scheduled = task(30, {
    type: 'sched', zoneId: 9, schedDate: '2026-10-20', planDate: '2026-10-20', schedTime: '08:00',
    repeat: 'daily', reminderEnabled: true, remindBeforeMinutes: 30,
  });
  const deferral = createQuestDeferral(scheduled, 'not-today', { requestedAt, timeline, zones });
  const deferred = { ...scheduled, questDeferral: deferral };
  const nextZoneStart = new Date('2026-10-15T10:00:00').getTime();
  expect(nextTaskReminderAt(deferred, now, timeline)).toBe(nextZoneStart);
  expect(taskPreReminderAt(deferred, nextZoneStart)).toBeUndefined();
  expect(recurringReminderRows(deferred, now, timeline)).toEqual([{ id: 30, title: 'Задача 30', dueMs: nextZoneStart, fired: false }]);
  expect(recurringReminderRows({ ...deferred, firedSched: true }, now, timeline)).toEqual([]);
});
