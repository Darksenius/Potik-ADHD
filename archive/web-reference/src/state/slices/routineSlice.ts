import type { AppSlice } from '../store';
import type { RecurItem } from '../../types';
import { fmtDate } from '../../utils/date';

const UNIT_STEP: Record<string, number> = { check: 1, count: 1, ml: 100, min: 1, kcal: 50 };
/** ULBL — рядок 1240 */
export const ULBL: Record<string, string> = { check: '', count: 'раз', ml: 'мл', min: 'хв', kcal: 'ккал' };

export interface RoutineSlice {
  recur: RecurItem[];

  /** rcInc() — рядок 1787 */
  incRecur: (id: string) => void;
  /** rcDec() — рядок 1788 */
  decRecur: (id: string) => void;
  /** rcTogCheck() — рядок 1789 */
  toggleRecurCheck: (id: string) => void;
  /** delRecur(id) — рядок 1790 */
  deleteRecur: (id: string) => void;
  /** doSaveRecur(isNeg) — рядки 1791–1800, без DOM-читання (параметри від компонента) */
  addRecur: (nm: string, unit: RecurItem['unit'], color: string, isNeg: boolean, negXp?: number) => void;
}

export const createRoutineSlice: AppSlice<RoutineSlice> = (set, get) => ({
  recur: [],

  incRecur: (id) => {
    const migrated = get().tasks.find(task => task.legacyRoutineId === id && !task.trashed);
    if (migrated) {
      if (migrated.type === 'negative') get().toggleTask(migrated.id);
      else if (migrated.type === 'counter') get().changeCounter(migrated.id, migrated.counterStep || 1);
      else get().setTaskDone(migrated.id, true);
      return;
    }
    const r = get().recur.find((x) => x.id === id);
    if (!r) return;
    const step = r.step || UNIT_STEP[r.unit] || 1;
    set((s) => ({
      recur: s.recur.map((x) =>
        x.id === id ? (r.unit === 'check' ? { ...x, done: true } : { ...x, val: x.val + step }) : x
      ),
    }));
    if (r.neg) {
      get().incrementNegCount();
      get().award(-(r.negXp || 5));
    } else {
      get().award(2);
    }
  },

  decRecur: (id) => {
    const migrated = get().tasks.find(task => task.legacyRoutineId === id && !task.trashed);
    if (migrated) {
      if (migrated.type === 'counter') get().changeCounter(migrated.id, -(migrated.counterStep || 1));
      else if (migrated.type === 'negative') {
        const step = Number.isFinite(migrated.counterStep) && migrated.counterStep! > 0 ? migrated.counterStep! : 1;
        set((s) => ({ tasks: s.tasks.map(task => task.id === migrated.id
          ? { ...task, counter: Math.max(0, (task.counter || 0) - step), counterDay: fmtDate(new Date()) } : task) }));
      }
      return;
    }
    const r = get().recur.find((x) => x.id === id);
    if (!r) return;
    const step = r.step || UNIT_STEP[r.unit] || 1;
    set((s) => ({
      recur: s.recur.map((x) => (x.id === id ? { ...x, val: Math.max(0, x.val - step) } : x)),
    }));
  },

  toggleRecurCheck: (id) => {
    const migrated = get().tasks.find(task => task.legacyRoutineId === id && !task.trashed);
    if (migrated) {
      if (migrated.type === 'negative' || migrated.type === 'simple') get().toggleTask(migrated.id);
      return;
    }
    const r = get().recur.find((x) => x.id === id);
    if (!r) return;
    const nextDone = !r.done;
    set((s) => ({ recur: s.recur.map((x) => (x.id === id ? { ...x, done: nextDone } : x)) }));
    if (nextDone) {
      if (r.neg) {
        get().incrementNegCount();
        get().award(-(r.negXp || 5));
      } else {
        get().award(5);
      }
    }
  },

  deleteRecur: (id) => {
    const migrated = get().tasks.find(task => task.legacyRoutineId === id && !task.trashed);
    if (migrated) { get().deleteTask(migrated.id); return; }
    set((s) => ({ recur: s.recur.filter((r) => r.id !== id) }));
  },

  addRecur: (nm, unit, color, isNeg, negXp) => {
    const name = nm.trim();
    if (!name) return;
    void color;
    const type = isNeg ? 'negative' : unit === 'check' ? 'simple' : 'counter';
    const task = get().createTask(name, type, null, '');
    get().saveTask(task.id, {
      title: name,
      type,
      repeat: 'daily',
      counterUnit: unit === 'check' ? 'count' : unit,
      counterStep: UNIT_STEP[unit] || 1,
      counterTarget: 0,
      counterEnabled: type === 'counter',
      negXp: isNeg ? negXp || 5 : undefined,
    });
  },
});
