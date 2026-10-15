import type { QuestTimelineSegment, Task, TaskHistoryEvent } from '../types';
import { fmtDate, isDateKey, localDateTimeMs, normalizeTime, toMinutes } from './date';
import { completedTaskOccurrences } from './taskHistory';

/** A scheduled task has one day for both its calendar entry and its reminder. */
export function taskPlanDate(task: Task): string | undefined {
  if (task.type === 'sched' && isDateKey(task.schedDate)) return task.schedDate;
  return isDateKey(task.planDate) ? task.planDate : undefined;
}

export interface TaskProjectionContext {
  now: Date;
  timeline: readonly QuestTimelineSegment[];
}

/** The pending occurrence can move while its permanent recurrence stays intact. */
export function taskDeferredOccurrenceAt(task: Task, context?: TaskProjectionContext): number | undefined {
  const d = task.questDeferral;
  if (!d || task.done || task.trashed || task.someday) return undefined;
  const now = context?.now.getTime() ?? Date.now();
  if (d.kind === 'next-zone') {
    if (d.endAt === undefined || now < d.endAt) return Math.max(d.startAt, Math.min(now, d.endAt ?? d.startAt));
    const source = context?.timeline.find(s => s.zoneId === d.sourceZoneId && s.startAt >= d.endAt! && s.endAt > now);
    return source ? Math.max(source.startAt, now) : undefined;
  }
  if (task.zoneId == null) return d.startAt;
  const target = context?.timeline.find(s => s.zoneId === d.targetZoneId && s.endAt > Math.max(now, d.startAt));
  return target ? Math.max(target.startAt, d.startAt) : context ? undefined : d.startAt;
}

export function taskDisplayPlanDate(task: Task, context?: TaskProjectionContext): string | undefined {
  if (task.questDeferral && !task.done) {
    const at = taskDeferredOccurrenceAt(task, context);
    return fmtDate(new Date(at ?? task.questDeferral.startAt));
  }
  return taskPlanDate(task);
}

export function isDeferredOccurrenceOn(task: Task, day: string, context?: TaskProjectionContext): boolean {
  return !!task.questDeferral && !task.done && taskDisplayPlanDate(task, context) === day;
}

export function taskOccursOn(task: Task, day: string, context?: TaskProjectionContext): boolean {
  if (task.trashed || task.someday || !isDateKey(day)) return false;
  if (task.questDeferral && !task.done) {
    const movedDay = taskDisplayPlanDate(task, context)!;
    if (movedDay === day) return true;
    // Moving one unfinished occurrence does not delete future schedule previews.
    if (day < movedDay || !isCalendarRepeat(task)) return false;
  }
  if (isCalendarRepeat(task)) {
    const anchor = taskPlanDate(task) || fmtDate(new Date(task.created));
    return day >= anchor && repeatDueOn(task, new Date(day + 'T12:00:00'));
  }
  if (task.repeat === 'interval' && task.done && task.nextRepeatAt) return fmtDate(new Date(task.nextRepeatAt)) === day;
  return taskPlanDate(task) === day;
}

export function isCalendarRepeat(task: Task): boolean {
  return ['daily', 'weekly', 'weekdays', 'weekend', 'custom'].includes(task.repeat);
}

/** Projection in the planner does not mark future occurrences as completed. */
export function taskDoneOn(task: Task, day: string, history?: readonly TaskHistoryEvent[]): boolean {
  // A past date is a historical projection, not the task's current done flag.
  // Today must continue to reflect the live occurrence: a completed earlier
  // every-zone/interval occurrence cannot check off the new one after reset.
  if (history && day < fmtDate(new Date())) {
    const matches = (event: TaskHistoryEvent) => event.taskId === task.id && event.taskCreated === task.created && event.date === day;
    if (completedTaskOccurrences(history).some(matches)) return true;
    return !!task.done && task.doneDate === day && completedTaskOccurrences(history, [task]).some(matches);
  }
  if (task.repeat === 'interval' && task.nextRepeatAt && fmtDate(new Date(task.nextRepeatAt)) === day) return false;
  return task.done && (!isCalendarRepeat(task) || task.doneDate === day);
}

/** A future repeating row is a preview, not the stored completed occurrence. */
export function canTogglePlannedTask(task: Task, day: string, now = new Date()): boolean {
  if (isCalendarRepeat(task) && day !== fmtDate(now)) return false;
  if (task.repeat === 'interval') return day === fmtDate(now) && !(task.nextRepeatAt && task.nextRepeatAt > now.getTime());
  return true;
}

/** Capturing a thought does not put it on today's plan. Explicit recurring,
 * time-window and zone rules are separate ways to schedule availability. */
export function isTaskInInbox(task: Task): boolean {
  return !task.trashed && !task.someday && !task.done && !taskPlanDate(task) && !task.zoneId
    && (!task.repeat || task.repeat === 'none')
    && !['sched', 'alarm', 'timewin', 'zonelinked'].includes(task.type);
}

export function repeatDueOn(task: Task, date: Date): boolean {
  const day = (date.getDay() + 6) % 7;
  switch (task.repeat) {
    case 'daily': return true;
    case 'weekdays': return day < 5;
    case 'weekend': return day >= 5;
    case 'custom': return !!task.repeatDays?.[day];
    case 'weekly': {
      const anchor = taskPlanDate(task);
      const first = anchor ? new Date(anchor + 'T12:00:00') : new Date(task.created);
      return !Number.isFinite(first.getTime()) || date.getDay() === first.getDay();
    }
    case 'interval': return !!task.nextRepeatAt && date.getTime() >= task.nextRepeatAt;
    default: return false;
  }
}

export function isTaskInTimeWindow(task: Task, now: Date): boolean {
  if (task.type !== 'timewin') return true;
  const start = normalizeTime(task.windowStart);
  const end = normalizeTime(task.windowEnd);
  if (!start || !end) return true; // Invalid old data must remain accessible for repair.
  const minute = now.getHours() * 60 + now.getMinutes();
  const from = toMinutes(start);
  const to = toMinutes(end);
  return from <= to ? minute >= from && minute <= to : minute >= from || minute <= to;
}

export function isTaskAvailable(task: Task, now = new Date()): boolean {
  if (task.trashed || task.someday) return false;
  if (task.done) return true;
  const day = taskPlanDate(task);
  if (day && day > fmtDate(now)) return false;
  if (task.snoozeUntil && task.snoozeUntil > now.getTime()) return false;
  if (!isTaskInTimeWindow(task, now)) return false;
  if (task.type === 'timewin' && task.completedToday) return false;
  if (task.repeat && !['none', 'interval', 'everyzone'].includes(task.repeat) && !repeatDueOn(task, now)) return false;
  return true;
}

/** Priorities are day-level choices. Quest selection separately enforces the
 * active zone and exact time; an undated quick task can also be prioritized. */
export function canPrioritizeTask(task: Task, now = new Date()): boolean {
  return !task.done && task.type !== 'negative' && isTaskAvailable(task, now);
}

export function taskPreReminderAt(task: Task, due: number): number | undefined {
  if (task.questDeferral) return undefined;
  const minutes = task.remindBeforeMinutes || 0;
  if (task.type !== 'sched' || task.firedPre || task.reminderEnabled === false || !Number.isFinite(minutes) || minutes <= 0) return undefined;
  return due - minutes * 60000;
}

/** Shared browser/native snapshot reminder eligibility. Alarm dates are searched
 * by calendar day, preserving weekday rules across month and DST boundaries. */
export function questDeferralReminderAt(task: Task, timeline?: readonly QuestTimelineSegment[]): number | undefined {
  const deferral = task.questDeferral;
  if (!deferral) return undefined;
  if (deferral.kind === 'next-zone' || task.zoneId == null) return deferral.startAt;
  // Eligibility begins tomorrow at local midnight, but a zone-bound alarm
  // should ring only when that primary zone actually begins its next interval.
  const segment = timeline?.find(segment => segment.endAt > deferral.startAt && segment.zoneId === deferral.targetZoneId);
  return segment ? Math.max(segment.startAt, deferral.startAt) : undefined;
}

export function nextTaskReminderAt(task: Task, now = new Date(), timeline?: readonly QuestTimelineSegment[]): number | undefined {
  if (task.done || task.trashed || task.someday || task.reminderEnabled === false) return undefined;
  if (task.questDeferral && ['sched', 'alarm'].includes(task.type)) {
    const fired = task.type === 'sched' ? task.firedSched : task.alarmFired;
    if (fired) return undefined;
    return questDeferralReminderAt(task, timeline);
  }
  if (task.type === 'sched') {
    const due = localDateTimeMs(taskPlanDate(task), task.schedTime);
    if (due === undefined || (task.snoozeUntil && due < task.snoozeUntil)) return undefined;
    if (task.repeat && !['none', 'interval', 'everyzone'].includes(task.repeat) && !repeatDueOn(task, new Date(due))) return undefined;
    return due;
  }
  if (task.type !== 'alarm' || !normalizeTime(task.alarmTime)) return undefined;
  if ((!task.repeat || task.repeat === 'none') && task.alarmFired) return undefined;
  const minDue = Math.max(now.getTime() - 59999, task.snoozeUntil || 0);
  const plannedDay = taskPlanDate(task);
  for (let offset = 0; offset < 370; offset++) {
    const date = new Date(now);
    date.setDate(date.getDate() + offset);
    const day = fmtDate(date);
    if (plannedDay && day < plannedDay) continue;
    const due = localDateTimeMs(day, task.alarmTime)!;
    if (due < minDue) continue;
    if (task.repeat && !['none', 'interval', 'everyzone'].includes(task.repeat) && !repeatDueOn(task, date)) continue;
    if (task.repeat === 'interval' && task.nextRepeatAt && due < task.nextRepeatAt) continue;
    return due;
  }
  return undefined;
}
