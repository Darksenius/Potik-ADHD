import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { collectState, applyState, saveState, loadState, releaseSaveGuard, saveIsBlocked, checkBackupRecovery } from '../src/services/persistence';
import { importState, rollbackLastImport, IMPORT_ROLLBACK_KEY } from '../src/services/exportImport';
import { validateBackup } from '../src/services/backupValidation';
import { buildForegroundNotifPayload } from '../src/bridge/notifPayload';
import { nextTaskReminderAt } from '../src/utils/taskSchedule';
import { checkReminders } from '../src/services/reminders';
import { handleNativeEvent } from '../src/bridge/nativeBridge';
import { fmtDate } from '../src/utils/date';
import { repeatDueOn } from '../src/state/slices/lifecycleSlice';
import type { Task } from '../src/types';

let values: Map<string, string>;
const task = (id: number, extra: Partial<Task> = {}): Task => ({ id, title: 'Однакова назва', type: 'simple', done: false, someday: false, repeat: 'none', repeatDays: Array(7).fill(false), tags: [], created: '2026-10-08T09:00:00.000Z', ...extra });
test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  releaseSaveGuard();
  values = new Map();
  (globalThis as any).localStorage = { getItem: (k: string) => values.get(k) || null, setItem: (k: string, v: string) => values.set(k, v) };
  (globalThis as any).window = {};
});
test.afterEach(() => { delete (globalThis as any).window; delete (globalThis as any).localStorage; });

test('exported fields including empty arrays, long focus history and preferences fully restore', () => {
  useStore.setState({ tasks: [task(901)], qnotes: [{ id: 502, txt: 'Нотатка', date: '2026-10-08', time: '10:00', folder: 'impulse' }], notepad: 'Мій блокнот', xp: 31, xpTotal: 731, level: 4, focusChips: [], focusLog: Array.from({ length: 130 }, (_, i) => ({ val: String(i), start: '2026-10-08T09:00:00.000Z', end: null, date: '2026-10-08', time: '12:00' })), planRestDays: { '2026-10-10': true }, planDayLog: { '2026-10-07': { focus: [], energy: 2, routineDone: {}, priorities: [], tasksDone: 2, note: 'Історія' } }, preferences: { themeMode: 'light', reduceMotion: true, showGamification: false } });
  useStore.getState().recalculateZoneUsage();
  const before = collectState();
  useStore.setState(useStore.getInitialState(), true);
  expect(importState(before, 'restore')).toBe(1);
  const after = collectState();
  expect(after).toMatchObject({ ...before, nid: 902 });
  expect(after.focusLog).toHaveLength(130);
  expect(values.has(IMPORT_ROLLBACK_KEY)).toBe(true);
  expect(rollbackLastImport()).toBe(true);
  expect(useStore.getState().notepad).toBe('');
});

test('malformed backup cannot partially mutate data or replace rollback copy', () => {
  useStore.setState({ notepad: 'Зберегти', tasks: [task(400)] });
  const before = collectState();
  const malformed = [{ tasks: [null] }, { tasks: [task(5)], planDayZones: { '2026-10-08': [null] } }, { tasks: [task(5)], zones: [{ id: 1, nm: 'Зона', slots: 'broken' }] }, { tasks: [task(1), task(1)] }];
  for (const raw of malformed) {
    expect(() => importState(raw, 'restore')).toThrow();
    expect(collectState()).toEqual(before);
    expect(values.has(IMPORT_ROLLBACK_KEY)).toBe(false);
    expect(applyState(raw)).toBe(false);
  }
});

test('removed task types reject the entire import without changing current tasks or rollback', () => {
  useStore.setState({ notepad: 'Зберегти', tasks: [task(400)] });
  values.set(IMPORT_ROLLBACK_KEY, 'existing rollback');
  const before = collectState();
  for (const type of ['pomodoro', 'habit', 'kid']) {
    expect(() => importState({ tasks: [task(5), { ...task(6), type }] }, 'restore')).toThrow();
    expect(collectState()).toEqual(before);
    expect(values.get(IMPORT_ROLLBACK_KEY)).toBe('existing rollback');
  }
});

test('Inbox capture is excluded from Android daily snapshot until planned, simple tasks never schedule alarms', () => {
  const captured = useStore.getState().createTask('Купити батарейки', 'simple');
  useStore.getState().addQuickTaskFromShade('Думка зі шторки');
  expect(buildForegroundNotifPayload().taskList).toHaveLength(0);
  useStore.getState().saveTask(captured.id, { title: captured.title, type: 'simple', planDate: fmtDate(new Date()) });
  expect(buildForegroundNotifPayload().taskList.map(t => t.id)).toEqual([captured.id]);
  expect(buildForegroundNotifPayload().schedList).toHaveLength(0);
});

test('native capture, Today priority and moving day share the same eligibility without implicit completion', () => {
  expect(handleNativeEvent('capture_task:Зателефонувати майстру')).toBe(true);
  const captured = useStore.getState().tasks[0];
  useStore.getState().setPriority(0, captured.id);
  expect(useStore.getState().priorities).toEqual([null, null, null]);
  expect(handleNativeEvent('done_first')).toBe(false);
  expect(useStore.getState().tasks[0].done).toBe(false);
  expect(buildForegroundNotifPayload().taskList).toHaveLength(0);
  useStore.getState().saveTask(captured.id, { title: captured.title, type: 'simple', planDate: fmtDate(new Date()) });
  useStore.getState().setPriority(0, captured.id);
  useStore.getState().setPriority(1, captured.id);
  expect(useStore.getState().priorities).toEqual([null, captured.id, null]);
  const payload = buildForegroundNotifPayload();
  expect(payload.taskList[0].id).toBe(captured.id);
  expect(payload.urgentTasks).toContain(captured.title);
  expect(payload.body).toContain('🎯 1');
  const tomorrow = new Date(); tomorrow.setDate(tomorrow.getDate() + 1);
  useStore.getState().saveTask(captured.id, { title: captured.title, type: 'simple', planDate: fmtDate(tomorrow) });
  expect(buildForegroundNotifPayload().taskList).toHaveLength(0);
  expect(buildForegroundNotifPayload().body).not.toContain('🎯');
  expect(useStore.getState().tasks[0]).toMatchObject({ id: captured.id, planDate: fmtDate(tomorrow), done: false });
});

test('same-title tasks on different days survive merge and reimport adds no duplicates', () => {
  const raw = { tasks: [task(100, { type: 'sched', schedDate: '2026-10-09', schedTime: '10:00' }), task(101, { type: 'sched', schedDate: '2026-10-10', schedTime: '10:00' })] };
  expect(importState(raw, 'merge')).toBe(2);
  expect(importState(raw, 'merge')).toBe(0);
  expect(useStore.getState().tasks.map(t => t.schedDate)).toEqual(['2026-10-09', '2026-10-10']);
});

test('invalid native snapshot falls back to valid browser data', () => {
  values.set('flow_v2', JSON.stringify({ tasks: [task(404)], notepad: 'Цілий резерв', saveDate: 'Thu Oct 08 2026' }));
  (window as any).FlowBridge = { load: () => '{broken' };
  expect(loadState()).toBe('Thu Oct 08 2026');
  expect(useStore.getState().notepad).toBe('Цілий резерв');
  expect(saveIsBlocked()).toBe(false);
});

test('local storage quota does not block native save and ack failure does not write browser state', () => {
  const writes: string[] = [];
  (window as any).FlowBridge = { save: (v: string) => writes.push(v), saveNotif: () => {}, writeBackup: () => {}, saveWithEvents: (v: string, ids: string) => { if (ids !== '[]') return false; writes.push(v); return true; } };
  (globalThis as any).localStorage.setItem = () => { throw new Error('QuotaExceededError'); };
  expect(saveState()).toBe(true);
  expect(writes).toHaveLength(1);
  expect(saveState([1])).toBe(false);
  expect(writes).toHaveLength(1);
});

test('corrupt startup data remains intact despite autosave attempts', () => {
  const corrupt = '{tasks:';
  values.set('flow_v2', corrupt);
  expect(loadState()).toBeNull();
  expect(checkBackupRecovery().kind).toBe('unreadable');
  expect(saveState()).toBe(false);
  expect(values.get('flow_v2')).toBe(corrupt);
});

test('interval repeat waits across midnight and resets only after its elapsed interval', () => {
  const before = new Date('2026-10-09T00:01:00+03:00');
  const due = new Date('2026-10-15T12:00:00+03:00');
  const t = task(4, { repeat: 'interval', repeatMs: 604800000, done: true, doneDate: '2026-10-08', nextRepeatAt: due.getTime() });
  useStore.setState({ tasks: [t] });
  useStore.getState().resetRepeatingTasksFor(before);
  expect(useStore.getState().tasks[0].done).toBe(true);
  useStore.getState().resetRepeatingTasksFor(due);
  expect(useStore.getState().tasks[0].done).toBe(false);
});

test('weekday alarms skip Saturday, 9:00 is normalized and someday tasks produce no reminders', () => {
  const t = task(10, { type: 'alarm', alarmTime: '9:00', repeat: 'weekdays' });
  const saturday = new Date('2026-10-10T08:00:00+03:00');
  const due = nextTaskReminderAt(t, saturday)!;
  expect(new Date(due).getDay()).toBe(1);
  expect(repeatDueOn(t, saturday)).toBe(false);
  useStore.setState({ tasks: [task(11, { type: 'sched', schedDate: '2026-10-09', schedTime: '9:00', someday: true }), task(12, { type: 'sched', schedDate: '2026-10-09', schedTime: '9:00', reminderEnabled: false })] });
  expect(buildForegroundNotifPayload().schedList).toHaveLength(0);
});

test('reminder offsets default to none and explicit offsets produce exact native times', () => {
  useStore.setState({ tasks: [task(11, { type: 'sched', schedDate: '2030-10-09', schedTime: '9:00', planDate: '2030-10-09' })] });
  let reminders = buildForegroundNotifPayload().schedList;
  expect(reminders).toHaveLength(1);
  expect(Number.isFinite(reminders[0].dueMs)).toBe(true);
  useStore.setState(s => ({ tasks: s.tasks.map(t => ({ ...t, remindBeforeMinutes: 0 })) }));
  expect(buildForegroundNotifPayload().schedList).toHaveLength(1);
  useStore.setState(s => ({ tasks: s.tasks.map(t => ({ ...t, remindBeforeMinutes: 15 })) }));
  reminders = buildForegroundNotifPayload().schedList;
  expect(reminders).toHaveLength(2);
  expect(reminders[0].dueMs - reminders[1].dueMs).toBe(15 * 60000);
});

test('validation remains usable without Object.fromEntries on Android 9 WebView', () => {
  const original = Object.fromEntries;
  let result;
  Object.fromEntries = undefined as any;
  try { result = validateBackup({ tasks: [task(1)], planDayLog: {}, preferences: { themeMode: 'system' } }).tasks; }
  finally { Object.fromEntries = original; }
  expect(result).toHaveLength(1);
});

test('native UI ticks keep delayed main and advance notifications pending until Android confirms delivery', () => {
  const now = new Date(); now.setSeconds(0, 0);
  const later = new Date(now.getTime() + 15 * 60000);
  const time = (d: Date) => String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  (window as any).Capacitor = { isNativePlatform: () => true };
  useStore.setState({ tasks: [
    task(41, { type: 'sched', schedDate: fmtDate(now), schedTime: time(now) }),
    task(42, { type: 'sched', schedDate: fmtDate(later), schedTime: time(later), remindBeforeMinutes: 15 }),
  ] });
  checkReminders(); checkReminders();
  expect(useStore.getState().alarmTaskIds).toEqual([41, 42]);
  const snapshot = buildForegroundNotifPayload().schedList;
  expect(snapshot.find(t => t.id === 41)?.fired).toBe(false);
  expect(snapshot.find(t => t.id === '42_pre')).toMatchObject({ dueMs: now.getTime(), fired: false });
  expect(handleNativeEvent('sched_fired:41:' + now.getTime())).toBe(true);
  expect(handleNativeEvent('sched_fired:42_pre:' + now.getTime())).toBe(true);
  expect(useStore.getState().tasks[0].firedSched).toBe(true);
  expect(useStore.getState().tasks[1].firedPre).toBe(true);
});

test('delivery receipt for a previous deadline cannot disable a rescheduled task', () => {
  useStore.setState({ tasks: [task(41, { type: 'sched', schedDate: '2030-10-09', schedTime: '10:00', remindBeforeMinutes: 15 })] });
  const due = new Date('2030-10-09T10:00:00').getTime();
  expect(handleNativeEvent('sched_fired:41:' + (due - 86400000))).toBe(false);
  expect(handleNativeEvent('sched_fired:41_pre:' + (due - 86400000 - 900000))).toBe(false);
  expect(useStore.getState().tasks[0].firedSched).not.toBe(true);
  expect(useStore.getState().tasks[0].firedPre).not.toBe(true);
  expect(handleNativeEvent('sched_fired:41:' + due)).toBe(true);
});
