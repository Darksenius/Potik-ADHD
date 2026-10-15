import { useStore, type RootState } from '../state/store';
import type { Task, TaskHistoryEvent } from '../types';
import { flushAutosave } from './autosave';
import { historyEvent, nextHistorySequence } from '../utils/taskHistory';
import { fmtDate } from '../utils/date';
import { isValidQuestDeferral } from '../utils/questPolicy';

const GLOBAL_KEYS = ['xp', 'xpTotal', 'level', 'done', 'dayXpGained', 'xpDay', 'unlocked', 'priorities', 'alarmTaskIds'] as const;
type Globals = Pick<RootState, typeof GLOBAL_KEYS[number]>;
export interface TaskUndoBefore { task: Task; globals: Globals }
interface PendingUndo extends TaskUndoBefore {
  after: Task; afterGlobals: Globals; kind: 'done' | 'defer'; sourceAt: number; expiresAt: number; message: string;
}
export interface TaskUndoNotice { expiresAt: number; message: string }
let pending: PendingUndo | null = null;
let notice: TaskUndoNotice | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));
const globals = (state: RootState): Globals => GLOBAL_KEYS.reduce((out, key) => ({ ...out, [key]: clone(state[key]) }), {} as Globals);
function publish(value: TaskUndoNotice | null) { notice = value; listeners.forEach(listener => { try { listener(); } catch { /* View listeners cannot break a task action. */ } }); }
export const getTaskUndoNotice = () => notice;
export function subscribeTaskUndo(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }
export function clearTaskUndo() { if (timer) clearTimeout(timer); pending = null; publish(null); }
export function captureTaskUndo(id: number): TaskUndoBefore | undefined {
  if (useStore.getState().progressionVersion !== 2) useStore.getState().initializeProgression();
  const state = useStore.getState(), task = state.tasks.find(t => t.id === id);
  return task ? { task: clone(task), globals: globals(state) } : undefined;
}
export function offerTaskUndo(before: TaskUndoBefore | undefined, kind: 'done' | 'defer', sourceAt = Date.now()) {
  if (!before) return;
  const state = useStore.getState(), after = state.tasks.find(t => t.id === before.task.id);
  if (!after || same(before.task, after)) return;
  if (timer) clearTimeout(timer);
  const message = kind === 'done' ? 'Виконано: ' + after.title : 'Перенесено: ' + after.title;
  pending = { ...before, after: clone(after), afterGlobals: globals(state), kind, sourceAt, expiresAt: sourceAt + 10000, message };
  publish(pending.expiresAt > Date.now() ? { message, expiresAt: pending.expiresAt } : null);
  timer = setTimeout(clearTaskUndo, Math.max(0, pending.expiresAt - Date.now()));
}

/** Restore only the last unchanged task action; never replace other tasks or newer edits. */
function restorePending(force = false): boolean {
  const undo = pending;
  if (!undo || !force && Date.now() > undo.expiresAt) { clearTaskUndo(); return false; }
  const state = useStore.getState(), task = state.tasks.find(t => t.id === undo.task.id);
  const changedKeys = GLOBAL_KEYS.filter(key => !same(undo.globals[key], undo.afterGlobals[key]));
  if (!task || !same(task, undo.after) || changedKeys.some(key => !same(state[key], undo.afterGlobals[key]))) {
    clearTaskUndo(); if (!force) state.showToast('Задачу вже змінено. Відкрий її, щоб повернути вручну.'); return false;
  }
  const patch = changedKeys.reduce((out, key) => ({ ...out, [key]: clone(undo.globals[key]) }), {});
  const taskHistory = undo.kind === 'done' && undo.after.occurrenceId
    ? [...state.taskHistory, historyEvent(undo.after, 'reopened', Date.now(), undo.after.occurrenceId, nextHistorySequence(state.taskHistory))]
    : state.taskHistory;
  useStore.setState({ ...patch, tasks: state.tasks.map(t => t.id === task.id ? clone(undo.task) : t), taskHistory, xpPopup: null, levelUpFlash: null });
  // Activity is a lasting fact even when the task action itself is undone.
  useStore.getState().checkAchievements();
  return true;
}
export function undoTaskAction(): boolean {
  const before = useStore.getState();
  if (!restorePending()) return false;
  if (!flushAutosave()) { useStore.setState(before); before.showToast('Не вдалося зберегти скасування. Спробуй ще раз.'); return false; }
  clearTaskUndo(); useStore.getState().showToast('Дію скасовано'); return true;
}

/** A durable undo tap can be consumed much later; validate the original tap interval. */
export function applyNativeTaskUndo(raw: string): boolean {
  let value: { id?: unknown; created?: unknown; kind?: unknown; sourceAt?: unknown; undoneAt?: unknown; before?: unknown };
  try { value = JSON.parse(raw); } catch { return false; }
  if (!value || !Number.isSafeInteger(value.id) || typeof value.created !== 'string'
    || !['done', 'defer'].includes(String(value.kind)) || typeof value.sourceAt !== 'number' || typeof value.undoneAt !== 'number'
    || !Number.isFinite(value.sourceAt) || value.sourceAt <= 0 || !Number.isFinite(value.undoneAt) || value.undoneAt < value.sourceAt
    || value.undoneAt - value.sourceAt > 10000 || !value.before || typeof value.before !== 'object' || Array.isArray(value.before)) return false;
  const state = useStore.getState(), task = state.tasks.find(t => t.id === value.id && t.created === value.created);
  if (!task || task.trashed) return false;
  if (pending?.task.id === task.id && pending.task.created === task.created && pending.kind === value.kind
    && pending.sourceAt === value.sourceAt && restorePending(true)) { clearTaskUndo(); return true; }
  const before = value.before as Partial<Task>;
  if (before.questDeferral != null && !isValidQuestDeferral(before.questDeferral)) return false;
  if (before.completionDeferral != null && !isValidQuestDeferral(before.completionDeferral)) return false;
  if (value.kind === 'defer' && (task.done || task.questDeferral?.requestedAt !== value.sourceAt)) return false;
  const original = value.kind === 'done' ? state.taskHistory.filter(event => event.taskId === task.id && event.taskCreated === task.created
    && event.kind === 'completed' && event.at === value.sourceAt).slice(-1)[0] : undefined;
  // A delayed Undo of yesterday's completion changes that occurrence's history,
  // not the current day's reset task or counter.
  let taskHistory = state.taskHistory;
  if (value.kind === 'done' && !original) return false;
  if (original) {
    const last = taskHistory.filter(event => event.occurrenceId === original.occurrenceId).slice(-1)[0];
    if (last?.kind !== 'completed' || last.at !== value.sourceAt) return false;
    taskHistory = [...taskHistory, { ...original, kind: 'reopened' as const, at: value.undoneAt,
      sequence: nextHistorySequence(taskHistory), id: original.occurrenceId + '#undo:' + value.undoneAt } as TaskHistoryEvent];
  }
  if (value.kind === 'done' && (!task.done || task.doneDate !== fmtDate(new Date(value.sourceAt)) || task.occurrenceId !== original?.occurrenceId)) {
    if (!original) return false;
    useStore.setState({ taskHistory }); return true;
  }
  if (value.kind === 'done' && before.done !== false) return false;
  const updated: Task = { ...task };
  const fields = ['done', 'doneDate', 'doneAt', 'questDeferral', 'completionDeferral', 'snoozeUntil', 'firedSched', 'firedPre', 'alarmFired',
    'completedToday', 'zoneDoneToday', 'nextRepeatAt', 'occurrenceId'] as const;
  for (const key of fields) {
    const v = before[key];
    if (v == null) delete updated[key];
    else if (['done', 'firedSched', 'firedPre', 'alarmFired', 'completedToday', 'zoneDoneToday'].includes(key)) {
      if (typeof v !== 'boolean') return false;
      Object.assign(updated, { [key]: v });
    } else if (['snoozeUntil', 'nextRepeatAt'].includes(key)) {
      if (typeof v !== 'number' || !Number.isFinite(v)) return false;
      Object.assign(updated, { [key]: v });
    } else if (key === 'questDeferral' || key === 'completionDeferral') Object.assign(updated, { [key]: v });
    else { if (typeof v !== 'string') return false; Object.assign(updated, { [key]: v }); }
  }
  // With no live before/after snapshot, retain completion credit as ordinary
  // reopening does; this prevents delayed Undo/re-complete from farming XP.
  useStore.setState({ tasks: state.tasks.map(t => t.id === task.id ? updated : t), taskHistory });
  clearTaskUndo(); return true;
}
