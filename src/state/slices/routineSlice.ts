import type { AppSlice } from '../store';
import type { RecurItem } from '../../types';

const UNIT_STEP: Record<string, number> = { check: 1, count: 1, ml: 100, min: 1, kcal: 50 };
/** ULBL — рядок 1240 */
export const ULBL: Record<string, string> = { check: '', count: 'раз', ml: 'мл', min: 'хв', kcal: 'ккал' };

/** Початкові рутини — перенесено 1:1 з www/index.html, рядки 1180–1184 (сідові дані). */
const SEED_RECUR: RecurItem[] = [
  { id: 'r1', nm: 'Вода', color: '#4f8ef7', unit: 'ml', val: 0, done: false, step: 100, neg: false, negXp: 0 },
  { id: 'r2', nm: 'Розтяжка', color: '#7ed321', unit: 'min', val: 0, done: false, step: 2, neg: false, negXp: 0 },
  { id: 'r3', nm: 'Перекур', color: '#e24b4a', unit: 'count', val: 0, done: false, step: 1, neg: true, negXp: 5 },
];

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
  recur: SEED_RECUR,

  incRecur: (id) => {
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
    const r = get().recur.find((x) => x.id === id);
    if (!r) return;
    const step = r.step || UNIT_STEP[r.unit] || 1;
    set((s) => ({
      recur: s.recur.map((x) => (x.id === id ? { ...x, val: Math.max(0, x.val - step) } : x)),
    }));
  },

  toggleRecurCheck: (id) => {
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
    set((s) => ({ recur: s.recur.filter((r) => r.id !== id) }));
  },

  addRecur: (nm, unit, color, isNeg, negXp) => {
    const name = nm.trim();
    if (!name) return;
    const item: RecurItem = {
      id: 'r' + Date.now(),
      nm: name,
      color,
      unit,
      val: 0,
      done: false,
      step: UNIT_STEP[unit] || 1,
      neg: isNeg,
      negXp: isNeg ? negXp || 5 : 0,
    };
    set((s) => ({ recur: [...s.recur, item] }));
  },
});
