import type { Task } from '../types';
import { fmtDate, isDateKey, localDateTimeMs, normalizeTime, toMinutes } from './date';

/** A scheduled task has one day for both its calendar entry and its reminder. */
export function taskPlanDate(task: Task): string | undefined {
  if (task.type === 'sched' && isDateKey(task.schedDate)) return task.schedDate;
  return isDateKey(task.planDate) ? task.planDate : undefined;
}

export function taskOccursOn(task: Task, day: string): boolean {
  return !task.trashed && !task.someday && taskPlanDate(task) === day;
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

export function taskPreReminderAt(task: Task, due: number): number | undefined {
  const minutes = task.remindBeforeMinutes || 0;
  if (task.type !== 'sched' || task.firedPre || task.reminderEnabled === false || !Number.isFinite(minutes) || minutes <= 0) return undefined;
  return due - minutes * 60000;
}

/** Shared browser/native snapshot reminder eligibility. Alarm dates are searched
 * by calendar day, preserving weekday rules across month and DST boundaries. */
export function nextTaskReminderAt(task: Task, now = new Date()): number | undefined {
  if (task.done || task.trashed || task.someday || task.reminderEnabled === false) return undefined;
  if (task.type === 'sched') {
    const due = localDateTimeMs(taskPlanDate(task), task.schedTime);
    if (due === undefined || (task.snoozeUntil && due < task.snoozeUntil)) return undefined;
    if (task.repeat && !['none', 'interval', 'everyzone'].includes(task.repeat) && !repeatDueOn(task, new Date(due))) return undefined;
    return due;
  }
  if (task.type !== 'alarm' || !normalizeTime(task.alarmTime)) return undefined;
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
