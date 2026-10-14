import type { PlanDayZoneOverride, QuestDeferral, QuestDeferralKind, QuestTimelineSegment, Task, Zone } from '../types';
import { addDaysDs, fmtDate, localDateTimeMs, normalizeTime, toMinutes } from './date';
import { isCalendarRepeat, isTaskInInbox, isTaskInTimeWindow, repeatDueOn, taskPlanDate } from './taskSchedule';

export type { QuestDeferral, QuestDeferralKind, QuestTimelineSegment } from '../types';

export interface QuestTimelineInput {
  zones: readonly Zone[];
  dayBlocks: (day: string) => readonly PlanDayZoneOverride[];
  planDayOff?: Record<string, readonly number[]>;
  now?: Date;
  days?: number;
}

export interface QuestContext {
  now: Date;
  timeline: readonly QuestTimelineSegment[];
  zones: readonly Zone[];
  priorities?: readonly (number | null)[];
}

export type QuestUnavailableReason =
  | 'done'
  | 'journal'
  | 'trashed'
  | 'someday'
  | 'future-date'
  | 'snoozed'
  | 'not-due'
  | 'outside-time-window'
  | 'timewin-completed'
  | 'wrong-zone'
  | 'missing-zone'
  | 'unassigned-zone'
  | 'deferred-until';

export interface QuestAvailability {
  eligible: boolean;
  reason?: QuestUnavailableReason;
  currentZoneId: number;
  availableAt?: number;
}

export type RankedQuestTask = Task & { priorityIndex: number };

function localMidnight(day: string): Date {
  return new Date(day + 'T00:00:00');
}

function timeRange(start: string, end: string): { start: number; end: number } | undefined {
  const from = toMinutes(start);
  let to = toMinutes(end);
  if (to === 0) to = 1440;
  if (!Number.isFinite(from) || !Number.isFinite(to) || from < 0 || from >= 1440 || to <= from || to > 1440) return undefined;
  return { start: from, end: to };
}

export interface QuestZoneRange {
  zoneId: number;
  start: number;
  end: number;
  priority: number;
  order: number;
  planned?: boolean;
}

function primaryZoneAt(ranges: readonly QuestZoneRange[], minute: number): number {
  let best: QuestZoneRange | undefined;
  for (const range of ranges) {
    if (minute < range.start || minute >= range.end) continue;
    if (!best || range.priority > best.priority || (range.priority === best.priority && range.order < best.order)) best = range;
  }
  return best?.zoneId ?? 0;
}

export function getQuestDayRanges(input: QuestTimelineInput, day: string): QuestZoneRange[] {
  const off = input.planDayOff?.[day] || [];
  const ranges: QuestZoneRange[] = [];
  let order = 0;
  input.zones.forEach(zone => {
    if (zone.off || zone.active === false || (zone.bound && !zone.actPin) || off.includes(zone.id)) return;
    (zone.slots || []).forEach(slot => {
      const range = timeRange(slot.s, slot.e);
      if (range) ranges.push({ zoneId: zone.id, ...range, priority: zone.prio || 1, order: order++ });
    });
  });
  input.dayBlocks(day).forEach(block => {
    const zone = input.zones.find(candidate => candidate.id === block.zoneId);
    if (!zone || zone.off) return;
    const range = timeRange(block.s, block.e);
    if (range) ranges.push({ zoneId: zone.id, ...range, priority: (zone.prio || 1) + 10, order: order++, planned: true });
  });
  return ranges;
}

/** Persist wall-clock intent so Android can reconstruct dates after a timezone change. */
export function buildQuestWallDays(input: QuestTimelineInput): import('../types').NotifQuestWallDay[] {
  const firstDay = fmtDate(input.now || new Date());
  return Array.from({ length: input.days || 35 }, (_, offset) => {
    const date = addDaysDs(firstDay, offset);
    const ranges = getQuestDayRanges(input, date);
    const minutes = new Set([0, 1440]);
    ranges.forEach(range => { minutes.add(range.start); minutes.add(range.end); });
    const boundaries = [...minutes].sort((a, b) => a - b);
    const segments: import('../types').NotifQuestWallDay['segments'] = [];
    for (let i = 0; i < boundaries.length - 1; i++) {
      const zoneId = primaryZoneAt(ranges, boundaries[i]);
      const previous = segments[segments.length - 1];
      if (previous?.zoneId === zoneId) previous.endMinute = boundaries[i + 1];
      else segments.push({ zoneId, startMinute: boundaries[i], endMinute: boundaries[i + 1] });
    }
    return { date, segments };
  });
}

/**
 * Resolves the same primary zone the app uses: baseline slots are filtered by
 * active/bound/day-off state, then effective day blocks win with their planned
 * priority. Scanning real local minutes makes segment boundaries correct over
 * 23-hour and 25-hour DST days. Zone 0 explicitly represents free time.
 */
export function buildQuestTimeline(input: QuestTimelineInput): QuestTimelineSegment[] {
  const now = input.now || new Date();
  const days = Number.isSafeInteger(input.days) && (input.days || 0) > 0 ? input.days! : 35;
  const firstDay = fmtDate(now);
  const start = localMidnight(firstDay);
  const end = localMidnight(addDaysDs(firstDay, days));
  const endAt = end.getTime();
  let dayCursor = start.getTime();
  const segments: QuestTimelineSegment[] = [];

  const append = (zoneId: number, from: number, to: number) => {
    if (to <= from) return;
    const previous = segments[segments.length - 1];
    if (previous && previous.zoneId === zoneId && previous.endAt === from) previous.endAt = to;
    else segments.push({ zoneId, startAt: from, endAt: to });
  };

  while (dayCursor < endAt) {
    const dayStart = new Date(dayCursor);
    const day = fmtDate(dayStart);
    const nextDay = localMidnight(addDaysDs(day, 1));
    const dayEnd = Math.min(nextDay.getTime(), endAt);
    const ranges = getQuestDayRanges(input, day);

    // Most days need only the authored slot boundaries. A real-time minute
    // scan is reserved for DST transition days where local wall-clock minutes
    // repeat or disappear, preserving the same primary-zone decisions there.
    if (dayEnd - dayCursor !== 24 * 60 * 60 * 1000) {
      let cursor = dayCursor;
      while (cursor < dayEnd) {
        const instant = new Date(cursor);
        const zoneId = primaryZoneAt(ranges, instant.getHours() * 60 + instant.getMinutes());
        const next = Math.min(cursor + 60000, dayEnd);
        append(zoneId, cursor, next);
        cursor = next;
      }
    } else {
      const minutes = new Set<number>([0, 1440]);
      ranges.forEach(range => { minutes.add(range.start); minutes.add(range.end); });
      const boundaries = [...minutes].sort((a, b) => a - b).map(minute => {
        if (minute === 1440) return dayEnd;
        return new Date(dayStart.getFullYear(), dayStart.getMonth(), dayStart.getDate(), Math.floor(minute / 60), minute % 60).getTime();
      });
      for (let index = 0; index < boundaries.length - 1; index++) {
        const from = boundaries[index];
        const to = boundaries[index + 1];
        const instant = new Date(from);
        append(primaryZoneAt(ranges, instant.getHours() * 60 + instant.getMinutes()), from, to);
      }
    }
    dayCursor = dayEnd;
  }
  return segments;
}

export function getCurrentQuestSegment(timeline: readonly QuestTimelineSegment[], now = new Date()): QuestTimelineSegment | undefined {
  const at = now.getTime();
  return timeline.find(segment => segment.startAt <= at && at < segment.endAt);
}

function deferralAllowsTask(task: Task, context: QuestContext, current?: QuestTimelineSegment): QuestAvailability | undefined {
  const deferral = task.questDeferral;
  if (!deferral) return undefined;
  const nowMs = context.now.getTime();
  const currentZoneId = current?.zoneId ?? 0;

  if (nowMs < deferral.startAt) return { eligible: false, reason: 'deferred-until', currentZoneId, availableAt: deferral.startAt };
  if (deferral.kind === 'next-zone') {
    if (deferral.endAt === undefined) return { eligible: false, reason: 'wrong-zone', currentZoneId };
    if (nowMs < deferral.endAt) {
      return currentZoneId === deferral.targetZoneId
        ? { eligible: true, currentZoneId }
        : { eligible: false, reason: 'wrong-zone', currentZoneId };
    }
    if (currentZoneId === deferral.sourceZoneId && !!current && current.startAt >= deferral.endAt) {
      return { eligible: true, currentZoneId };
    }
    const returnSegment = context.timeline.find(segment => segment.startAt >= Math.max(nowMs, deferral.endAt!) && segment.zoneId === deferral.sourceZoneId);
    return { eligible: false, reason: 'wrong-zone', currentZoneId, availableAt: returnSegment?.startAt };
  }

  // An unbound task becomes universal after 23 hours. A linked task remains
  // deferred until its own zone next becomes primary, including later returns.
  if (task.zoneId == null) return { eligible: true, currentZoneId };
  if (currentZoneId === deferral.targetZoneId && !!current) {
    return { eligible: true, currentZoneId };
  }
  const targetSegment = context.timeline.find(segment => segment.endAt > Math.max(nowMs, deferral.startAt) && segment.zoneId === deferral.targetZoneId);
  return { eligible: false, reason: 'wrong-zone', currentZoneId,
    availableAt: targetSegment ? Math.max(targetSegment.startAt, deferral.startAt) : undefined };
}

function isDoneForCurrentOccurrence(task: Task, now: Date): boolean {
  if (!task.done) return false;
  const today = fmtDate(now);
  const calendarRepeat = !!task.repeat && ['daily', 'weekly', 'weekdays', 'weekend', 'custom'].includes(task.repeat);
  const completionDay = task.doneDate || task.completionCreditDate;
  if (calendarRepeat && completionDay && completionDay < today && repeatDueOn(task, now)) return false;
  if (task.repeat === 'interval' && task.nextRepeatAt && task.nextRepeatAt <= now.getTime()) return false;
  return true;
}

function baseAvailability(task: Task, context: QuestContext, currentZoneId: number): QuestAvailability {
  const now = context.now;
  const nowMs = now.getTime();
  const today = fmtDate(now);
  if (task.trashed) return { eligible: false, reason: 'trashed', currentZoneId };
  if (isDoneForCurrentOccurrence(task, now)) return { eligible: false, reason: 'done', currentZoneId };
  if (task.someday) return { eligible: false, reason: 'someday', currentZoneId };

  const planDate = taskPlanDate(task);
  if (planDate && planDate > today) return { eligible: false, reason: 'future-date', currentZoneId };
  if (task.snoozeUntil && task.snoozeUntil > nowMs) return { eligible: false, reason: 'snoozed', currentZoneId, availableAt: task.snoozeUntil };
  if (task.type === 'timewin' && task.completedToday && (!task.doneDate || task.doneDate === today)) return { eligible: false, reason: 'timewin-completed', currentZoneId };
  if (task.repeat && !['none', 'interval', 'everyzone'].includes(task.repeat) && !repeatDueOn(task, now)) {
    return { eligible: false, reason: 'not-due', currentZoneId };
  }
  if (task.repeat === 'interval' && task.nextRepeatAt && task.nextRepeatAt > nowMs) {
    return { eligible: false, reason: 'not-due', currentZoneId, availableAt: task.nextRepeatAt };
  }

  if (task.type === 'sched' && task.schedTime) {
    const due = localDateTimeMs(isCalendarRepeat(task) ? today : taskPlanDate(task), normalizeTime(task.schedTime));
    if (due !== undefined && due > nowMs) return { eligible: false, reason: 'not-due', currentZoneId, availableAt: due };
  }
  if (task.type === 'alarm' && task.alarmTime) {
    const due = localDateTimeMs(today, normalizeTime(task.alarmTime));
    if (due !== undefined && due > nowMs) return { eligible: false, reason: 'not-due', currentZoneId, availableAt: due };
  }
  if (!isTaskInTimeWindow(task, now)) {
    return { eligible: false, reason: 'outside-time-window', currentZoneId };
  }

  // The simple quick-capture path is intentionally universal immediately while
  // retaining the task in Inbox for later planning.
  const quickUnplanned = ['simple', 'check', 'counter', 'note', 'ctx'].includes(task.type)
    && !planDate && task.zoneId == null;
  if (isTaskInInbox(task) && !quickUnplanned) {
    return { eligible: false, reason: 'not-due', currentZoneId };
  }
  return { eligible: true, currentZoneId };
}

/**
 * Shared browser/native eligibility policy. This filters every date, time and
 * zone constraint before priority ranking; a priority never makes a task fit.
 */
export function getQuestTaskAvailability(task: Task, context: QuestContext): QuestAvailability {
  const current = getCurrentQuestSegment(context.timeline, context.now);
  const currentZoneId = current?.zoneId ?? 0;
  if (task.trashed) return { eligible: false, reason: 'trashed', currentZoneId };
  if (task.someday) return { eligible: false, reason: 'someday', currentZoneId };
  if (task.type === 'negative') return { eligible: false, reason: 'journal', currentZoneId };
  if (isDoneForCurrentOccurrence(task, context.now)) return { eligible: false, reason: 'done', currentZoneId };
  const missingZone = task.zoneId != null && !context.zones.some(zone => zone.id === task.zoneId);
  if (missingZone) return { eligible: false, reason: 'missing-zone', currentZoneId };
  if (task.type === 'zonelinked' && task.zoneId == null) return { eligible: false, reason: 'unassigned-zone', currentZoneId };

  const deferred = deferralAllowsTask(task, context, current);
  if (deferred) return deferred;

  const base = baseAvailability(task, context, currentZoneId);
  if (!base.eligible) return base;

  if (task.zoneId != null && task.zoneId !== currentZoneId) {
    return { eligible: false, reason: 'wrong-zone', currentZoneId };
  }
  return { eligible: true, currentZoneId };
}

export function selectQuestTasks(tasks: readonly Task[], context: QuestContext, limit = 5): RankedQuestTask[] {
  const priorities = context.priorities || [];
  return tasks
    .map((task, order) => ({ task, order, priorityIndex: priorities.indexOf(task.id) }))
    .filter(({ task }) => getQuestTaskAvailability(task, context).eligible)
    .sort((a, b) => {
      const ap = a.priorityIndex < 0 ? Number.MAX_SAFE_INTEGER : a.priorityIndex;
      const bp = b.priorityIndex < 0 ? Number.MAX_SAFE_INTEGER : b.priorityIndex;
      return ap - bp || a.order - b.order;
    })
    .slice(0, Math.max(0, limit))
    .map(({ task, priorityIndex }) => ({ ...task, priorityIndex }));
}

/** Builds the exact object persisted by browser and native deferral actions. */
export function createQuestDeferral(
  task: Task,
  kind: QuestDeferralKind,
  input: Omit<QuestContext, 'now'> & { requestedAt: number; zones: readonly Zone[] },
): QuestDeferral {
  const requestedAt = Number.isFinite(input.requestedAt) ? input.requestedAt : Date.now();
  const requestedDate = new Date(requestedAt);
  const sourceZoneId = getCurrentQuestSegment(input.timeline, requestedDate)?.zoneId ?? 0;

  if (kind === 'next-zone') {
    const day = fmtDate(requestedDate);
    const next = input.timeline.find(segment =>
      segment.startAt > requestedAt && fmtDate(new Date(segment.startAt)) === day && segment.zoneId !== sourceZoneId,
    );
    if (next) return {
      kind: 'next-zone',
      requestedAt,
      sourceZoneId,
      targetZoneId: next.zoneId,
      startAt: next.startAt,
      endAt: next.endAt,
    };
  }

  if (task.zoneId != null) {
    const tomorrow = localMidnight(addDaysDs(fmtDate(requestedDate), 1)).getTime();
    return { kind: 'not-today', requestedAt, sourceZoneId, targetZoneId: task.zoneId, startAt: tomorrow };
  }
  return { kind: 'not-today', requestedAt, sourceZoneId, targetZoneId: 0, startAt: requestedAt + 23 * 60 * 60 * 1000 };
}

export function isValidQuestDeferral(value: unknown): value is QuestDeferral {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const item = value as Record<string, unknown>;
  const validId = (id: unknown) => typeof id === 'number' && Number.isSafeInteger(id) && id >= 0;
  const validAt = (at: unknown) => typeof at === 'number' && Number.isFinite(at);
  if (!['next-zone', 'not-today'].includes(item.kind as string)
    || !validAt(item.requestedAt) || !validId(item.sourceZoneId) || !validId(item.targetZoneId) || !validAt(item.startAt)) return false;
  if (item.kind === 'next-zone') return validAt(item.endAt) && (item.endAt as number) > (item.startAt as number);
  return item.endAt === undefined;
}

export function questDeferralLabel(task: Task, zones: readonly Zone[], now = new Date()): string | undefined {
  const deferral = task.questDeferral;
  if (!deferral) return undefined;
  const zoneName = (id: number) => zones.find(zone => zone.id === id)?.nm || ('зона #' + id);
  const time = (stamp: number) => new Date(stamp).toLocaleString('uk', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  if (deferral.kind === 'next-zone') {
    if (now.getTime() < deferral.startAt) return 'Не зараз · ' + zoneName(deferral.targetZoneId) + ' з ' + time(deferral.startAt);
    if (deferral.endAt !== undefined && now.getTime() < deferral.endAt) return 'Не зараз · до ' + time(deferral.endAt) + ' у ' + zoneName(deferral.targetZoneId);
    return 'Не зараз · повернеться у ' + zoneName(deferral.sourceZoneId) + ' при наступному вході';
  }
  const target = task.zoneId != null ? ' · ' + zoneName(deferral.targetZoneId) : ' · будь-яка зона';
  return 'Не сьогодні · з ' + time(deferral.startAt) + target;
}
