import type { RecurItem, Task, TaskType } from '../types';
import { createTaskRecord } from '../utils/taskFactory';
import { fmtDate, isDateKey } from '../utils/date';

const LEGACY_CREATED = '1970-01-01T00:00:00.000Z';
const STEP_DEFAULT: Record<RecurItem['unit'], number> = { check: 1, count: 1, ml: 100, min: 1, kcal: 50 };

export interface RoutineMigrationResult {
  tasks: Task[];
  nid: number;
}

function localDay(savedDate?: string): string {
  if (!savedDate) return fmtDate(new Date());
  if (isDateKey(savedDate)) return savedDate;
  const parsed = new Date(savedDate);
  return Number.isFinite(parsed.getTime()) ? fmtDate(parsed) : fmtDate(new Date());
}

function isRetiredWaterSeed(routine: RecurItem): boolean {
  return routine.id === 'r1' && routine.unit === 'ml' && routine.nm === 'Вода' && !routine.neg;
}

function validStep(routine: RecurItem): number {
  return Number.isFinite(routine.step) && routine.step > 0 ? routine.step : STEP_DEFAULT[routine.unit];
}

function routineTask(routine: RecurItem, id: number, day: string): Task {
  const negative = routine.neg;
  const check = routine.unit === 'check';
  const type: TaskType = negative ? 'negative' : check ? 'simple' : 'counter';
  const task = createTaskRecord(id, routine.nm, type, null, null, '', LEGACY_CREATED);
  const savedCount = Number.isFinite(routine.val) ? Math.max(0, routine.val) : 0;
  const count = negative && check && routine.done ? Math.max(1, savedCount) : savedCount;
  task.legacyRoutineId = routine.id;
  task.repeat = 'daily';
  task.counterDay = day;
  if (negative) {
    task.counter = count;
    task.counterStep = validStep(routine);
    task.counterUnit = routine.unit === 'check' ? 'count' : routine.unit;
    task.counterTarget = 0;
    task.counterCreditedProgress = count;
    task.negXp = Number.isFinite(routine.negXp) ? routine.negXp : 5;
  } else if (check) {
    task.done = routine.done;
    task.doneDate = routine.done ? day : undefined;
    task.completionCredited = routine.done;
    task.completionCreditDate = routine.done ? day : undefined;
  } else {
    task.counter = count;
    task.counterStep = validStep(routine);
    task.counterUnit = routine.unit === 'check' ? 'count' : routine.unit;
    task.counterTarget = 0;
    task.counterCreditedProgress = count;
  }
  return task;
}

export function normalizeRoutineTasks(tasks: Task[], routines: RecurItem[], nid: number,
  savedDate?: string): RoutineMigrationResult {
  let nextId = Math.max(1, nid || 1, ...tasks.map(task => task.id + 1));
  const migrated = tasks.slice();
  const day = localDay(savedDate);

  for (const routine of routines) {
    if (isRetiredWaterSeed(routine) || migrated.some(task => task.legacyRoutineId === routine.id)) continue;
    migrated.push(routineTask(routine, nextId++, day));
  }

  return { tasks: migrated, nid: nextId };
}
