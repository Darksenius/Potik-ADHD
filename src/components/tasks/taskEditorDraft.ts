import type { Task, TaskType } from '../../types';
import { TASK_TYPE_LABELS } from '../../constants';

export type TaskEditorDraftValues = {
  title: string;
  type: TaskType;
  zoneId: string;
  folderId: string;
  repeat: Task['repeat'];
  repeatDays: boolean[];
  repeatInterval: number;
  repeatUnit: NonNullable<Task['repeatUnit']>;
  tagsText: string;
  date: string;
  time: string;
  alarmTime: string;
  winStart: string;
  winEnd: string;
  note: string;
  checklistDraft: string;
  cntTgt: number;
  counterEnabled: boolean;
  counterStep: number;
  counterUnit: NonNullable<Task['counterUnit']>;
  negXp: number;
  reminder: boolean;
  advance: string;
};

export type TaskEditorDraft = {
  version: 1;
  values: TaskEditorDraftValues;
  seed: { initialDate: string | null; preset: unknown };
};

const TASK_TYPES = new Set(Object.keys(TASK_TYPE_LABELS));
const REPEATS = new Set(['none', 'daily', 'weekly', 'weekdays', 'weekend', 'custom', 'interval', 'everyzone']);
const REPEAT_UNITS = new Set(['sec', 'min', 'hour', 'day', 'week', 'month']);
const COUNTER_UNITS = new Set(['count', 'ml', 'min', 'kcal']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isDraftValues(value: unknown): value is TaskEditorDraftValues {
  if (!isRecord(value)) return false;
  const strings = ['title', 'zoneId', 'folderId', 'tagsText', 'date', 'time', 'alarmTime', 'winStart', 'winEnd', 'note', 'checklistDraft', 'advance'];
  if (!strings.every(key => typeof value[key] === 'string')) return false;
  return typeof value.type === 'string' && TASK_TYPES.has(value.type)
    && typeof value.repeat === 'string' && REPEATS.has(value.repeat)
    && Array.isArray(value.repeatDays) && value.repeatDays.length === 7 && value.repeatDays.every(day => typeof day === 'boolean')
    && isFiniteNumber(value.repeatInterval) && typeof value.repeatUnit === 'string' && REPEAT_UNITS.has(value.repeatUnit)
    && isFiniteNumber(value.cntTgt) && typeof value.counterEnabled === 'boolean'
    && isFiniteNumber(value.counterStep) && typeof value.counterUnit === 'string' && COUNTER_UNITS.has(value.counterUnit)
    && isFiniteNumber(value.negXp) && typeof value.reminder === 'boolean';
}

function isDraftSeed(value: unknown): value is TaskEditorDraft['seed'] {
  if (!isRecord(value) || !(value.initialDate === null || typeof value.initialDate === 'string')) return false;
  if (value.preset === null) return true;
  if (!isRecord(value.preset)) return false;
  const preset = value.preset;
  return (preset.type === undefined || typeof preset.type === 'string' && TASK_TYPES.has(preset.type))
    && (preset.repeat === undefined || typeof preset.repeat === 'string' && REPEATS.has(preset.repeat))
    && (preset.counterUnit === undefined || typeof preset.counterUnit === 'string' && COUNTER_UNITS.has(preset.counterUnit))
    && (preset.counterStep === undefined || isFiniteNumber(preset.counterStep))
    && (preset.counterTarget === undefined || isFiniteNumber(preset.counterTarget));
}

export function readTaskEditorDraft(raw: string): TaskEditorDraft | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (!isRecord(value) || value.version !== 1 || !isDraftValues(value.values) || !isDraftSeed(value.seed)) return null;
    return { version: 1, values: value.values, seed: value.seed };
  } catch {
    return null;
  }
}

export function writeTaskEditorDraft(values: TaskEditorDraftValues, initialDate: string | null, preset: unknown): string {
  return JSON.stringify({ version: 1, values, seed: { initialDate, preset } } satisfies TaskEditorDraft);
}
