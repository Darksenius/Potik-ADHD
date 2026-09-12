import type { AppSlice } from '../store';
import type { FocusLogEntry } from '../../types';
import { fmtDate } from '../../utils/date';

/**
 * ══ FOCUS TRACKER ══ + ══ PRIORITIES ══  (www/index.html, рядки 1604–1761)
 * Повністю перенесено. У початковій пропозиції структури для цього домену
 * не було теки серед components/ — пропоную components/focus/ (FocusChips,
 * FocusLog, PriorityPicker), бо вкладка «Стан» оригіналу й так рендерить їх
 * разом із рутиною (routineSlice).
 */
export interface FocusSlice {
  /** 3 слоти пріоритетних задач на сьогодні (id задачі або null) */
  priorities: (number | null)[];
  focusChips: string[];
  focusLog: FocusLogEntry[];
  currentFocus: string;

  /** setFocus(el,val) — рядки 1648–1663, без DOM/queueSave (те — робота компонента/persistence-хука) */
  setFocus: (val: string) => void;
  /** clearFocus() — рядки 1664–1676 */
  clearFocus: () => void;
  /** addFocusChip(val) — рядки 1643–1647 */
  addFocusChip: (val: string) => void;
  /** removeFocusChip(val) — рядки 1638–1642 */
  removeFocusChip: (val: string) => void;
  /** selPrio(id) — рядок 1739 (без closeModal — робота компонента) */
  setPriority: (slot: number, taskId: number) => void;
  /** clearPrio(slot) — рядок 1740 */
  clearPriority: (slot: number) => void;
}

export const createFocusSlice: AppSlice<FocusSlice> = (set, get) => ({
  priorities: [null, null, null],
  focusChips: ['Потік', 'Робота', 'Дитина', 'Навчання', 'Cybersec', 'Гра', 'Відпочинок'],
  focusLog: [],
  currentFocus: '',

  setFocus: (val) => {
    const s = get();
    const now = new Date();
    let focusLog = s.focusLog;
    if (s.currentFocus && s.currentFocus !== val) {
      const last = focusLog[focusLog.length - 1];
      if (last && !last.end) {
        const dur = Math.round((now.getTime() - new Date(last.start).getTime()) / 60000);
        focusLog = focusLog.map((e, i) => (i === focusLog.length - 1 ? { ...e, end: now.toISOString(), dur } : e));
      }
    }
    const entry: FocusLogEntry = {
      val,
      start: now.toISOString(),
      end: null,
      date: fmtDate(now),
      time: now.toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' }),
    };
    set({ currentFocus: val, focusLog: [...focusLog, entry] });
  },

  clearFocus: () => {
    const s = get();
    if (!s.currentFocus) return;
    const now = new Date();
    let focusLog = s.focusLog;
    const last = focusLog[focusLog.length - 1];
    if (last && !last.end) {
      const dur = Math.round((now.getTime() - new Date(last.start).getTime()) / 60000);
      focusLog = focusLog.map((e, i) => (i === focusLog.length - 1 ? { ...e, end: now.toISOString(), dur } : e));
    }
    set({ currentFocus: '', focusLog });
  },

  addFocusChip: (val) => {
    if (val && !get().focusChips.includes(val)) {
      set((s) => ({ focusChips: [...s.focusChips, val] }));
    }
    get().setFocus(val);
  },

  removeFocusChip: (val) => {
    set((s) => ({ focusChips: s.focusChips.filter((v) => v !== val) }));
    if (get().currentFocus === val) get().clearFocus();
  },

  setPriority: (slot, taskId) => {
    set((s) => {
      const priorities = s.priorities.slice();
      priorities[slot] = taskId;
      return { priorities };
    });
  },

  clearPriority: (slot) => {
    set((s) => {
      const priorities = s.priorities.slice();
      priorities[slot] = null;
      return { priorities };
    });
  },
});
