import type { AppSlice } from '../store';
import type { Task } from '../../types';
import { fmtDate } from '../../utils/date';

/**
 * ══ ПОВТОРЮВАНІ ЗАДАЧІ ══ + ══ DAILY RESET ══
 * (www/index.html, рядки 2884–3062). Повністю перенесено, окрім firedA.clear()
 * (Set дедуплікації спрацьованих будильників — частина ще не перенесеної
 * СИСТЕМИ СПОВІЩЕНЬ, рядки 4190–4253).
 *
 * _lastResetDate — умисно НЕ в реактивному Zustand-стані (як і в оригіналі
 * це проста module-level змінна, не S.поле): це технічний guard для
 * ідемпотентності перевірки раз/хв, а не дані додатку.
 */
let lastResetDate = new Date().toDateString();

/** repeatDueOn(t,dt) — рядки 2887–2898. Чи повторювана задача активна саме в цей день. */
export function repeatDueOn(t: Task, dt: Date): boolean {
  const r = t && t.repeat;
  if (!r || r === 'none' || r === 'everyzone') return false;
  const wd = (dt.getDay() + 6) % 7;
  if (r === 'daily') return true;
  if (r === 'weekdays') return wd <= 4;
  if (r === 'weekend') return wd >= 5;
  if (r === 'custom') return !!(t.repeatDays && t.repeatDays[wd]);
  if (r === 'weekly') {
    const c = t.created ? new Date(t.created) : null;
    return c ? (c.getDay() + 6) % 7 === wd : true;
  }
  if (r === 'interval') return true; // час-базоване — принаймні зводимо щодня
  return false;
}

export interface LifecycleSlice {
  /** resetRepeatingTasksFor(dt) — рядки 2902–2913 */
  resetRepeatingTasksFor: (dt: Date) => void;
  /** dailyResetIfNeeded(savedDate) — рядки 2914–2929. Викликати ОДИН РАЗ при завантаженні. */
  dailyResetIfNeeded: (savedDate: string | null) => boolean;
  /** checkDailyReset() — рядки 3037–3052. Викликати з setInterval(...,60000) — фоновий скид опівночі. */
  checkDailyReset: () => void;
}

export const createLifecycleSlice: AppSlice<LifecycleSlice> = (set, get) => ({
  resetRepeatingTasksFor: (dt) => {
    const ds = fmtDate(dt);
    set((s) => ({
      tasks: s.tasks.map((t) => {
        if (!t.done || t.trashed) return t;
        if (!t.repeat || t.repeat === 'none' || t.repeat === 'everyzone') return t;
        if (t.doneDate === ds) return t; // вже виконано саме сьогодні — не чіпати
        if (!repeatDueOn(t, dt)) return t; // сьогодні не за графіком — лишити як є
        const next: Task = { ...t, done: false, doneDate: '', doneAt: undefined };
        if (Array.isArray(next.items)) next.items = next.items.map((it) => ({ ...it, done: false }));
        if (next.type === 'counter') next.counter = 0;
        return next;
      }),
    }));
  },

  dailyResetIfNeeded: (savedDate) => {
    const today = new Date().toDateString();
    if (savedDate && savedDate !== today) {
      set((s) => ({
        recur: s.recur.map((r) => ({ ...r, val: 0, done: false })),
        tasks: s.tasks.map((t) => {
          if (t.type !== 'zonelinked' && t.type !== 'timewin' && t.type !== 'sched' && t.type !== 'alarm') return t;
          const next = { ...t };
          if (next.type === 'zonelinked') next.zoneDoneToday = false;
          if (next.type === 'timewin') {
            next.completedToday = false;
            next.done = false;
          }
          if (next.type === 'sched') next.firedSched = false;
          if (next.type === 'alarm') next.alarmFired = false;
          return next;
        }),
      }));
      get().resetRepeatingTasksFor(new Date());
      // TODO: firedA.clear() — Set дедуплікації будильників, ще не перенесено
      // (СИСТЕМА СПОВІЩЕНЬ, рядки 4190–4253).
      return true;
    }
    return false;
  },

  checkDailyReset: () => {
    const today = new Date().toDateString();
    if (today === lastResetDate) return;
    lastResetDate = today;
    set((s) => ({
      recur: s.recur.map((r) => ({ ...r, val: 0, done: false })),
      tasks: s.tasks.map((t) => {
        if (t.type !== 'zonelinked' && t.type !== 'timewin' && t.type !== 'sched' && t.type !== 'alarm') return t;
        const next = { ...t };
        if (next.type === 'zonelinked') next.zoneDoneToday = false;
        if (next.type === 'timewin') {
          next.completedToday = false;
          next.done = false;
        }
        if (next.type === 'sched') next.firedSched = false;
        if (next.type === 'alarm') next.alarmFired = false; // щоденний будильник знову спрацює
        return next;
      }),
    }));
    get().resetRepeatingTasksFor(new Date());
    // TODO: firedA.clear() — див. коментар вище.
    console.log('FLOW: daily reset (фоновий)');
  },
});
