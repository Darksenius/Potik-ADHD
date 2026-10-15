import type { Task, TaskHistoryEvent } from '../types';
import { fmtDate, localDateTimeMs } from './date';

export function taskIdentity(task: Pick<Task, 'id' | 'created'>): string {
  return `${task.id}@${task.created}`;
}

/** Deferral and Undo continue an occurrence; a lifecycle reset starts another. */
export function taskOccurrenceId(task: Task, at: number, zoneKey?: string): string {
  if (task.occurrenceId) return task.occurrenceId;
  const origin = task.questDeferral?.requestedAt ?? at;
  const day = task.counterDay || task.planDate || fmtDate(new Date(origin));
  const suffix = task.repeat === 'everyzone'
    ? `zone:${task.zoneOccurrenceKey || zoneKey || day + ':' + (task.zoneId || 0)}`
    : task.repeat === 'interval' ? `interval:${at}`
      : task.repeat && task.repeat !== 'none' ? `day:${day}` : 'once';
  return `${taskIdentity(task)}|${suffix}`;
}

export function historyEvent(task: Task, kind: TaskHistoryEvent['kind'], at: number,
  occurrenceId: string, sequence: number, date = fmtDate(new Date(at)), time?: string): TaskHistoryEvent {
  return {
    id: `${occurrenceId}#${sequence}:${at}:${kind}`, occurrenceId,
    taskId: task.id, taskCreated: task.created, title: task.title, type: task.type,
    kind, sequence, at, date, time: time ?? new Date(at).toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' }),
    zoneId: task.zoneId, counter: task.counter,
    items: task.items?.map(item => ({ ...item })),
  };
}

export function nextHistorySequence(history: readonly TaskHistoryEvent[]): number {
  return history.reduce((max, event) => Math.max(max, event.sequence || 0), -1) + 1;
}

/** Preserve only facts actually present in an older snapshot, never infer lost history. */
export function seedKnownCompletions(history: readonly TaskHistoryEvent[], tasks: readonly Task[]): TaskHistoryEvent[] {
  const result = history.slice();
  const known = new Set(history.map(event => event.occurrenceId));
  let sequence = nextHistorySequence(history);
  for (const task of tasks) {
    if (!task.done || !task.doneDate || task.type === 'negative') continue;
    const at = localDateTimeMs(task.doneDate, task.doneAt || '00:00');
    if (at === undefined) continue;
    const occurrenceId = taskOccurrenceId(task, at, task.zoneOccurrenceKey);
    if (!known.has(occurrenceId)) {
      known.add(occurrenceId);
      result.push(historyEvent(task, 'completed', at, occurrenceId, sequence++, task.doneDate, task.doneAt || ''));
    }
  }
  return result;
}

const NO_TASKS: readonly Task[] = [];
const completionCache = new WeakMap<readonly TaskHistoryEvent[], WeakMap<readonly Task[], TaskHistoryEvent[]>>();

/** Last action of each occurrence determines its completion; snapshots retain titles. */
export function completedTaskOccurrences(history: readonly TaskHistoryEvent[], tasks: readonly Task[] = NO_TASKS): TaskHistoryEvent[] {
  let byTasks = completionCache.get(history);
  const cached = byTasks?.get(tasks);
  if (cached) return cached;
  const latest = new Map<string, TaskHistoryEvent>();
  for (const event of seedKnownCompletions(history, tasks)) {
    const prior = latest.get(event.occurrenceId);
    if (!prior || (event.sequence || 0) > (prior.sequence || 0)
      || (event.sequence || 0) === (prior.sequence || 0) && (event.at > prior.at
        || event.at === prior.at && event.kind === 'reopened')) latest.set(event.occurrenceId, event);
  }
  const completed = [...latest.values()].filter(event => event.kind === 'completed');
  if (!byTasks) { byTasks = new WeakMap(); completionCache.set(history, byTasks); }
  byTasks.set(tasks, completed);
  return completed;
}

export function remapHistoryIdentity(value: string, taskId: number, created: string, mappedId: number): string {
  const prefix = `${taskId}@${created}|`;
  return value.startsWith(prefix) ? `${mappedId}@${created}|${value.slice(prefix.length)}` : value;
}
