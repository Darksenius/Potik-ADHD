import type { AppSlice } from '../store';

/**
 * ══ UI ══ — навігаційний і фільтраційний стан, що реально потрібен
 * кільком компонентам одночасно (тому й живе в глобальному сторі).
 *
 * СВІДОМО ВИКЛЮЧЕНО (буде локальним useState у відповідному компоненті,
 * КОЛИ той буде побудований на Кроці 3 — не глобальний стан):
 *   - editId, editRepDays        — форма редактора задачі (рядки 1213)
 *   - slotRows, curPickerSlot    — форма редактора зони (рядок 1215)
 *   - pomTimers                  — активні інтервали Pomodoro (рядок 1212) —
 *                                  це handle-и setInterval, не дані; мають
 *                                  жити в ref/хуку компонента, НЕ в сторі
 *                                  (реактивний стан з таймер-хендлами — антипатерн)
 *   - firedA                     — Set дедуплікації спрацьованих будильників
 *                                  (рядок 1212), теж імперативна бухгалтерія
 */
export type PageId = 'main' | 'widget' | 'readme';
export type TabId = 'tasks' | 'ideas' | 'planner' | 'stats' | 'zones' | 'notes' | 'state';

export interface UiSlice {
  theme: 'light' | 'dark';
  currentPage: PageId;
  currentTab: TabId;

  /** ctxFilter — рядок 1212: 'all' | 'f:<folderId>' | 'neg' */
  taskFilter: string;
  /** curFolder — рядок 1214: фільтр папки нотаток */
  noteFolder: string;
  showDone: boolean;
  inboxOpen: boolean;
  shadeOpen: boolean;
  /** wFlt/wMode — рядок 1214: фільтр і режим віджета шторки */
  widgetFilter: string;
  widgetMode: 'note' | 'task';

  /** planWeekOffset/planSelectedDay — рядок 1224, НЕ персистяться в оригіналі */
  planWeekOffset: number;
  planSelectedDay: string | null;

  /**
   * Керує відкриттям редактора задачі (майбутній TaskEditor, Крок 3):
   * число — редагувати задачу з цим id; 'new' — нова задача (заміна
   * openEdit(null)); null — редактор закритий. Покриває і виклик з
   * nativeBridge (note:'...' → openEdit(null), рядок 4411), і клік ✎
   * на картці задачі (openEdit(t.id), рядок 2235).
   */
  editorTaskId: number | 'new' | null;

  toggleTheme: () => void;
  showPage: (page: PageId) => void;
  switchTab: (tab: TabId) => void;
  setTaskFilter: (filter: string) => void;
  setNoteFolder: (folder: string) => void;
  toggleShowDone: () => void;
  requestNewTaskEditor: () => void;
  requestEditTask: (id: number) => void;
  closeTaskEditor: () => void;
  /** planSelDay(ds) — рядок 3400, без scrollIntoView (репорт 11.06 — совало інтерфейс) */
  setPlanSelectedDay: (ds: string) => void;
  /** weekShift(dir) — рядок 3325 */
  shiftPlanWeek: (dir: -1 | 1) => void;
  /** toast(msg) — рядки 2690-2699, DOM-тост замінено на похідний стан */
  toastMessage: string | null;
  showToast: (msg: string) => void;
  dismissToast: () => void;
  /**
   * showBackupBanner() — рядки 2995-3034. Наповнюється ОДИН РАЗ при старті
   * (checkBackupRecovery() у services/persistence.ts), UI — BackupBanner.tsx.
   */
  backupBanner: { taskCount: number; saveDate: string; data: unknown } | 'unreadable' | null;
  setBackupBanner: (v: UiSlice['backupBanner']) => void;
  dismissBackupBanner: () => void;
}

export const createUiSlice: AppSlice<UiSlice> = (set) => ({
  theme: 'dark',
  currentPage: 'main',
  currentTab: 'tasks',
  taskFilter: 'all',
  noteFolder: 'all',
  showDone: false,
  inboxOpen: false,
  shadeOpen: false,
  widgetFilter: 'all',
  widgetMode: 'note',
  planWeekOffset: 0,
  planSelectedDay: null,
  editorTaskId: null,
  toastMessage: null,
  backupBanner: null,

  // toggleTheme() — рядки 1171–1175. Застосування data-theme до <html> —
  // тепер робота useEffect у App.tsx, що читає це поле (див. App.tsx).
  toggleTheme: () => set((s) => ({ theme: s.theme === 'light' ? 'dark' : 'light' })),
  showPage: (page) => set({ currentPage: page }),
  switchTab: (tab) => set({ currentTab: tab }),
  setTaskFilter: (filter) => set({ taskFilter: filter }),
  setNoteFolder: (folder) => set({ noteFolder: folder }),
  toggleShowDone: () => set((s) => ({ showDone: !s.showDone })),
  requestNewTaskEditor: () => set({ editorTaskId: 'new' }),
  requestEditTask: (id) => set({ editorTaskId: id }),
  closeTaskEditor: () => set({ editorTaskId: null }),
  setPlanSelectedDay: (ds) => set({ planSelectedDay: ds }),
  shiftPlanWeek: (dir) => set((s) => ({ planWeekOffset: s.planWeekOffset + dir })),
  showToast: (msg) => set({ toastMessage: msg }),
  dismissToast: () => set({ toastMessage: null }),
  setBackupBanner: (v) => set({ backupBanner: v }),
  dismissBackupBanner: () => set({ backupBanner: null }),
});
