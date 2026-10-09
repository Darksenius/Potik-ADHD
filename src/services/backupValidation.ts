import type { AppState } from '../types';

type Obj = Record<string, unknown>;
type Check = (value: unknown, path: string) => unknown;
const fail = (path: string): never => { throw new Error(`Некоректні дані резервної копії: ${path}. Поточні дані не змінено.`); };
const object = (value: unknown, path: string): Obj => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fail(path);
  const result = value as Obj;
  if (Object.keys(result).some(key => ['__proto__', 'prototype', 'constructor'].includes(key))) return fail(path);
  return result;
};
const str: Check = (v, p) => typeof v === 'string' ? v : fail(p);
const num: Check = (v, p) => typeof v === 'number' && Number.isFinite(v) ? v : fail(p);
const id: Check = (v, p) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 ? v : fail(p);
const bool: Check = (v, p) => typeof v === 'boolean' ? v : fail(p);
const nullable = (check: Check): Check => (v, p) => v === null ? null : check(v, p);
const oneOf = (values: unknown[]): Check => (v, p) => values.includes(v) ? v : fail(p);
const array = (check: Check): Check => (v, p) => Array.isArray(v) ? v.map((item, i) => check(item, `${p}[${i}]`)) : fail(p);
const map = (check: Check): Check => (v, p) => Object.entries(object(v, p)).reduce<Obj>((result, [key, value]) => { result[key] = check(value, `${p}.${key}`); return result; }, {});
const shape = (fields: Record<string, Check>, required: string[] = [], defaults: Obj = {}): Check => (v, p) => {
  const value = object(v, p);
  for (const key of required) if (!(key in value)) fail(`${p}.${key}`);
  const result: Obj = { ...defaults, ...value };
  for (const [key, check] of Object.entries(fields)) if (key in value) result[key] = check(value[key], `${p}.${key}`);
  return result;
};
const strings = array(str);
const booleans = array(bool);
const slot = shape({ s: str, e: str }, ['s', 'e']);
const block = shape({ zoneId: id, s: str, e: str, ov: bool }, ['zoneId', 's', 'e']);
const ruleDay = shape({ tplId: str, rest: bool }, [], { tplId: '', rest: false });
const task = shape({
  id, title: str, type: oneOf(['simple', 'check', 'counter', 'note', 'alarm', 'sched', 'timewin', 'ctx', 'negative', 'zonelinked']),
  done: bool, someday: bool, zoneId: nullable(id), zoneColor: nullable(str), zoneName: nullable(str), folderId: nullable(str),
  expanded: bool, created: str, trashed: bool, doneDate: str, doneAt: str, snoozeUntil: num,
  items: array(shape({ text: str, done: bool }, ['text'], { done: false })),
  counter: num, counterTarget: num, cntHit: bool, negXp: num, note: str, alarmTime: str, alarmFired: bool,
  schedDate: str, schedTime: str, firedSched: bool, firedPre: bool, reminderEnabled: bool, remindBeforeMinutes: num, windowStart: str, windowEnd: str, completedToday: bool,
  ctxTags: strings, zoneDoneToday: bool,
  repeat: oneOf(['none', 'daily', 'weekly', 'weekdays', 'weekend', 'everyzone', 'interval', 'custom']),
  repeatDays: booleans, tags: strings, repeatMs: num, nextRepeatAt: num, repeatInterval: num,
  repeatUnit: oneOf(['sec', 'min', 'hour', 'day', 'week', 'month']), planDate: str, trashedAt: nullable(num),
}, ['id', 'title'], { type: 'simple', done: false, someday: false, created: '1970-01-01T00:00:00.000Z', repeat: 'none', repeatDays: [false, false, false, false, false, false, false], tags: [] });

const fields: Record<string, Check> = {
  tasks: array(task),
  zones: array(shape({ id, nm: str, color: str, slots: array(slot), desc: str, prio: num, active: bool, off: bool, bound: bool, actPin: bool }, ['id', 'nm'], { color: '#4f8ef7', slots: [] })),
  folders: array(shape({ id: str, nm: str, ico: str }, ['id', 'nm'], { ico: '📁' })),
  qnotes: array(shape({ id, txt: str, folder: str, time: str, date: str }, ['id', 'txt'], { folder: 'general', time: '', date: '' })),
  recur: array(shape({ id: str, nm: str, color: str, unit: oneOf(['check', 'count', 'ml', 'min', 'kcal']), val: num, done: bool, step: num, neg: bool, negXp: num }, ['id', 'nm'], { color: '#4f8ef7', unit: 'check', val: 0, done: false, step: 1, neg: false, negXp: 5 })),
  xp: num, xpTotal: num, level: num, done: num, streak: num, negCount: num, nid: id,
  unlocked: strings, energy: oneOf([0, 1, 2, 3, 4, 5]), lastEnergyXp: num, dayXpGained: num, xpDay: str,
  priorities: array(nullable(id)), focusChips: strings,
  focusLog: array(shape({ val: str, start: str, end: nullable(str), date: str, time: str, dur: num }, ['val', 'start'], { end: null, date: '', time: '' })),
  currentFocus: str, notepad: str, theme: oneOf(['light', 'dark']),
  preferences: shape({ themeMode: oneOf(['system', 'light', 'dark']), reduceMotion: bool, showGamification: bool }),
  rareEvents: array(shape({ id, nm: str, val: num, dir: oneOf(['up', 'down']), unit: str }, ['id', 'nm'], { val: 0, dir: 'up', unit: '' })),
  dayTemplates: array(shape({ id: str, name: str, blocks: array(block) }, ['id', 'name'], { blocks: [] })),
  weekTemplates: array(shape({ id: str, name: str, days: array(ruleDay) }, ['id', 'name'], { days: [] })),
  weekTplSeeded: bool,
  planItems: map(array(shape({ title: str, done: bool, isEvent: bool, time: str }, ['title'], { done: false }))),
  planRestDays: map(bool), planSchedules: map(str), planDayZones: map(array(block)), planDayOff: map(array(id)),
  planRules: array(shape({ id: str, name: str, start: str, end: str, days: array(ruleDay) }, ['id', 'start', 'end'], { name: '', days: [] })),
  planDayLog: map(shape({ focus: strings, energy: num, routineDone: map((v, p) => typeof v === 'boolean' ? v : str(v, p)), priorities: array(v => v), tasksDone: num, note: str }, [], { focus: [], energy: 0, routineDone: {}, priorities: [], tasksDone: 0 })),
  saveDate: str,
};

/** Validate a detached copy before any store mutation. Missing old-version fields
 * receive safe entity defaults; absent top-level fields remain absent for merges. */
export function validateBackup(raw: unknown): Partial<AppState> {
  const input = object(raw, 'файл');
  if (!Object.keys(fields).some(key => key in input)) fail('файл не містить даних Потоку');
  // Serializing also prevents imported object references from escaping into state.
  const copy = object(JSON.parse(JSON.stringify(input)), 'файл');
  const result: Obj = {};
  for (const [key, check] of Object.entries(fields)) if (key in copy) result[key] = check(copy[key], key);
  for (const key of ['tasks', 'zones', 'qnotes', 'folders', 'recur', 'dayTemplates', 'weekTemplates', 'rareEvents']) {
    const items = result[key] as Obj[] | undefined;
    if (items && new Set(items.map(item => item.id)).size !== items.length) fail(`${key}: повторені id`);
  }
  if (!result.preferences && (result.theme === 'dark' || result.theme === 'light')) result.preferences = { themeMode: result.theme, reduceMotion: false, showGamification: true };
  return result as Partial<AppState>;
}

/** Key order does not turn the same backup into duplicate content. */
export function sameBackupValue(a: unknown, b: unknown): boolean {
  const canonical = (v: unknown): unknown => {
    if (!v || typeof v !== 'object') return v;
    if (Array.isArray(v)) return v.map(canonical);
    return Object.keys(v as Obj).sort().reduce<Obj>((result, key) => {
      if ((v as Obj)[key] !== undefined) result[key] = canonical((v as Obj)[key]);
      return result;
    }, {});
  };
  return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
}
