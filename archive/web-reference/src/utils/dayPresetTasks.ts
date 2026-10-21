import taskTypeCatalog from '../content/task-types.json' with { type: 'json' };
import type { DayTemplateTask, Task, TaskType, Zone } from '../types';
import { createTaskRecord } from './taskFactory';
import { normalizeTime } from './date';

const taskTypes = new Set(Object.keys(taskTypeCatalog));

function text(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function idForEntry(): string {
  return `entry-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;
}

/** Explicitly whitelist reusable content so old occurrence state never enters a preset definition. */
export function sanitizeDayPresetTask(entry: DayTemplateTask): DayTemplateTask | null {
  const source = entry?.task;
  if (!entry?.id || !source || !taskTypes.has(source.type) || !source.title.trim()) return null;
  const definition: DayTemplateTask['task'] = { title: source.title.trim(), type: source.type };
  if (Number.isSafeInteger(source.zoneId)) definition.zoneId = source.zoneId;
  if (typeof source.note === 'string') definition.note = source.note;
  if (typeof source.folderId === 'string') definition.folderId = source.folderId;
  if (Array.isArray(source.tags)) definition.tags = source.tags.filter((tag): tag is string => typeof tag === 'string' && !!tag.trim()).map((tag) => tag.trim());
  if (source.type === 'check') {
    definition.items = (source.items || []).filter((item) => typeof item.text === 'string' && item.text.trim())
      .map((item) => ({ text: item.text.trim(), done: false }));
  }
  if (source.type === 'counter' || source.type === 'negative' || source.counterEnabled) {
    if (source.counterTarget !== undefined) {
      if (!Number.isSafeInteger(source.counterTarget) || source.counterTarget < 0) return null;
      definition.counterTarget = source.counterTarget;
    }
    if (typeof source.counterEnabled === 'boolean') definition.counterEnabled = source.counterEnabled;
    if (source.counterStep !== undefined) {
      if (!Number.isSafeInteger(source.counterStep) || source.counterStep < 1) return null;
      definition.counterStep = source.counterStep;
    }
    if (source.counterUnit) definition.counterUnit = source.counterUnit;
  }
  if (source.type === 'negative' && source.negXp !== undefined) {
    if (!Number.isSafeInteger(source.negXp) || source.negXp < 0) return null;
    definition.negXp = source.negXp;
  }
  if (source.type === 'alarm') {
    const alarmTime = normalizeTime(source.alarmTime || '');
    if (!alarmTime) return null;
    definition.alarmTime = alarmTime;
  }
  if (source.type === 'sched') {
    const schedTime = normalizeTime(source.schedTime || '');
    if (!schedTime) return null;
    definition.schedTime = schedTime;
    definition.reminderEnabled = source.reminderEnabled !== false;
    definition.remindBeforeMinutes = Number.isFinite(source.remindBeforeMinutes) ? Math.max(0, source.remindBeforeMinutes || 0) : 0;
  }
  if (source.type === 'timewin') {
    const windowStart = normalizeTime(source.windowStart || '07:00');
    const windowEnd = normalizeTime(source.windowEnd || '09:00');
    if (!windowStart || !windowEnd) return null;
    definition.windowStart = windowStart;
    definition.windowEnd = windowEnd;
  }
  if (source.type === 'ctx') definition.ctxTags = (source.ctxTags || []).filter((tag): tag is string => typeof tag === 'string' && !!tag.trim()).map((tag) => tag.trim());
  return { id: entry.id, task: definition };
}

/** Keep only authored task content. Runtime identity, progress, recurrence, and state are intentionally omitted. */
export function captureDayPresetTask(task: Task, entryId = idForEntry()): DayTemplateTask {
  return sanitizeDayPresetTask({ id: entryId, task }) || { id: entryId, task: { title: task.title, type: task.type } };
}

/** Build a fresh, one-off task occurrence from an authored entry. */
export function materializeDayPresetTask(
  entry: DayTemplateTask,
  templateId: string,
  date: string,
  id: number,
  zones: readonly Zone[]
): Task | null {
  const safeEntry = sanitizeDayPresetTask(entry);
  if (!safeEntry) return null;
  const definition = safeEntry.task;

  const validZone = Number.isSafeInteger(definition.zoneId)
    ? zones.find((zone) => zone.id === definition.zoneId) || null
    : null;
  const type: TaskType = definition.type;
  const zoneId = Number.isSafeInteger(definition.zoneId) ? definition.zoneId! : null;
  const base = createTaskRecord(id, definition.title.trim(), type, zoneId, validZone);
  const key = `${templateId}:${safeEntry.id}:${date}`;
  const occurrence: Task = {
    ...base,
    dayPresetKey: key,
    planDate: date,
    note: text(definition.note) ?? base.note,
    folderId: text(definition.folderId) ?? null,
    tags: Array.isArray(definition.tags) ? definition.tags.filter((tag): tag is string => typeof tag === 'string') : [],
    done: false,
    someday: false,
    repeat: 'none',
    repeatDays: [false, false, false, false, false, false, false],
    trashed: false,
    expanded: false,
    snoozeUntil: 0,
    doneDate: undefined,
    doneAt: undefined,
    completionCredited: false,
    completionCreditDate: undefined,
    occurrenceId: undefined,
    questDeferral: undefined,
    completionDeferral: undefined,
    counter: type === 'counter' || type === 'negative' ? 0 : undefined,
    counterDay: type === 'counter' || type === 'negative' ? date : undefined,
    counterCreditedProgress: type === 'counter' || type === 'negative' ? 0 : undefined,
    cntHit: false,
    alarmFired: false,
    firedSched: false,
    firedPre: false,
    completedToday: type === 'timewin' ? false : undefined,
    zoneDoneToday: type === 'zonelinked' ? false : undefined,
    zoneOccurrenceKey: undefined,
    nextRepeatAt: 0,
  };

  if (type === 'check') {
    occurrence.items = (definition.items || []).filter((item) => typeof item.text === 'string').map((item) => ({ text: item.text, done: false }));
  }
  if (type === 'counter' || type === 'negative' || definition.counterEnabled) {
    occurrence.counterTarget = Number.isFinite(definition.counterTarget) ? Math.max(0, definition.counterTarget!) : base.counterTarget;
    occurrence.counterStep = Number.isSafeInteger(definition.counterStep) && (definition.counterStep || 0) > 0 ? definition.counterStep : base.counterStep;
    occurrence.counterUnit = definition.counterUnit || base.counterUnit;
    occurrence.counterEnabled = type === 'counter' || (type !== 'negative' && definition.counterEnabled === true);
  }
  if (type === 'negative') occurrence.negXp = Number.isFinite(definition.negXp) ? Math.max(0, definition.negXp!) : base.negXp;
  if (type === 'alarm') occurrence.alarmTime = normalizeTime(definition.alarmTime || '') || '';
  if (type === 'sched') {
    occurrence.schedDate = date;
    occurrence.schedTime = normalizeTime(definition.schedTime || '') || '';
    occurrence.reminderEnabled = definition.reminderEnabled !== false;
    occurrence.remindBeforeMinutes = Number.isFinite(definition.remindBeforeMinutes) ? Math.max(0, definition.remindBeforeMinutes || 0) : 0;
  }
  if (type === 'timewin') {
    occurrence.windowStart = normalizeTime(definition.windowStart || '') || '07:00';
    occurrence.windowEnd = normalizeTime(definition.windowEnd || '') || '09:00';
  }
  if (type === 'ctx') occurrence.ctxTags = (definition.ctxTags || []).filter((tag): tag is string => typeof tag === 'string');
  return occurrence;
}

/** Append only missing preset occurrences; existing copies keep their progress and edits. */
export function appendDayPresetTasks(
  tasks: readonly Task[],
  nid: number,
  templateId: string,
  entries: readonly DayTemplateTask[],
  date: string,
  zones: readonly Zone[]
): { tasks: Task[]; nid: number } {
  const keys = new Set(tasks.map((task) => task.dayPresetKey).filter((key): key is string => !!key));
  const additions: Task[] = [];
  let nextId = nid;
  for (const entry of entries) {
    const safeEntry = sanitizeDayPresetTask(entry);
    if (!safeEntry) continue;
    const key = `${templateId}:${safeEntry.id}:${date}`;
    if (keys.has(key)) continue;
    const occurrence = materializeDayPresetTask(safeEntry, templateId, date, nextId, zones);
    if (!occurrence) continue;
    additions.push(occurrence);
    keys.add(key);
    nextId += 1;
  }
  return { tasks: additions.concat(tasks), nid: nextId };
}
