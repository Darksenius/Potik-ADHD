import { test, expect } from '@playwright/test';
import type { Task, Zone } from '../src/types';
import { buildQuestTimeline, createQuestDeferral, getQuestTaskAvailability } from '../src/utils/questPolicy';
import { taskOccursOn, taskDoneOn, nextTaskReminderAt } from '../src/utils/taskSchedule';

const task = (patch: Partial<Task> = {}): Task => ({ id: 1, title: 'Перенесена справа', type: 'simple',
  done: false, someday: false, repeat: 'none', repeatDays: [], tags: [], created: '2026-10-14T09:00:00', ...patch });

test('not-today in a continuous all-day zone becomes available at midnight without reopening', () => {
  const zones: Zone[] = [{ id: 7, nm: 'Вдома', color: '#abc', slots: [{ s: '00:00', e: '24:00' }] }];
  const requestedAt = new Date('2026-10-14T18:00:00').getTime();
  const timeline = buildQuestTimeline({ zones, dayBlocks: () => [], now: new Date(requestedAt), days: 3 });
  expect(timeline).toHaveLength(1);
  const original = task({ zoneId: 7, type: 'alarm', alarmTime: '18:00' });
  const deferral = createQuestDeferral(original, 'not-today', { requestedAt, timeline, zones });
  const pending = { ...original, questDeferral: deferral };
  const midnight = new Date('2026-10-15T00:00:00');
  expect(getQuestTaskAvailability(pending, { now: midnight, timeline, zones }).eligible).toBe(true);
  expect(nextTaskReminderAt(pending, new Date(requestedAt), timeline)).toBe(midnight.getTime());
  const refreshed = buildQuestTimeline({ zones, dayBlocks: () => [], now: midnight, days: 3 });
  expect(getQuestTaskAvailability(pending, { now: midnight, timeline: refreshed, zones }).eligible).toBe(true);
});

test('a zone-bound undated task deferred to tomorrow appears on its actual planned day', () => {
  const zones: Zone[] = [{ id: 5, nm: 'Вечір', color: '#abc', slots: [{ s: '18:00', e: '22:00' }] }];
  const now = new Date('2026-10-14T19:00:00');
  const timeline = buildQuestTimeline({ zones, dayBlocks: () => [], now, days: 3 });
  const original = task({ zoneId: 5 });
  const pending = { ...original, questDeferral: createQuestDeferral(original, 'not-today', {
    requestedAt: now.getTime(), timeline, zones,
  }) };
  expect(taskOccursOn(pending, '2026-10-15', { now, timeline })).toBe(true);
  expect(taskOccursOn(pending, '2026-10-14', { now, timeline })).toBe(false);
  expect(pending.planDate).toBeUndefined();
  const recurring = { ...pending, repeat: 'daily' as const };
  expect(taskOccursOn(recurring, '2026-10-16', { now, timeline })).toBe(true);
});

test('23-hour deferral has a calendar entry without changing its permanent date', () => {
  const now = new Date('2026-10-14T15:40:00');
  const original = task({ type: 'sched', schedDate: '2026-10-14', planDate: '2026-10-14', schedTime: '15:00' });
  const pending = { ...original, questDeferral: createQuestDeferral(original, 'not-today', {
    requestedAt: now.getTime(), timeline: [], zones: [],
  }) };
  expect(taskOccursOn(pending, '2026-10-15', { now, timeline: [] })).toBe(true);
  expect(taskOccursOn(pending, '2026-10-14', { now, timeline: [] })).toBe(false);
  expect(pending.schedDate).toBe('2026-10-14');
});

test('the next interval occurrence is not the completed occurrence in the calendar', () => {
  const completed = task({ repeat: 'interval', done: true, doneDate: '2026-10-14', planDate: '2026-10-14',
    nextRepeatAt: new Date('2026-10-15T09:00:00').getTime() });
  expect(taskOccursOn(completed, '2026-10-15')).toBe(true);
  expect(taskDoneOn(completed, '2026-10-15')).toBe(false);
});
