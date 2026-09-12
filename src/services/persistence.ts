import { useStore } from '../state/store';
import type { AppState } from '../types';
import { flowBridge } from '../bridge/nativeBridge';
import { buildForegroundNotifPayload } from '../bridge/notifPayload';

/** LS_KEY — рядок 1223. НЕ змінювати: інакше старі бекапи в localStorage «загубляться». */
export const LS_KEY = 'flow_v2';

/**
 * collectState() — рядки 4258–4289, ПОБІТОВО той самий набір ключів.
 * Якщо додаєш нове поле в стор і воно має зберігатись — додай його і сюди,
 * інакше воно мовчки не переживе перезапуск застосунку.
 */
export function collectState(): AppState {
  const s = useStore.getState();
  return {
    tasks: s.tasks,
    recur: s.recur,
    zones: s.zones,
    folders: s.folders,
    qnotes: s.qnotes,
    xp: s.xp,
    xpTotal: s.xpTotal,
    level: s.level,
    done: s.done,
    streak: s.streak,
    negCount: s.negCount,
    nid: s.nid,
    unlocked: s.unlocked,
    energy: s.energy,
    lastEnergyXp: s.lastEnergyXp || 0,
    dayXpGained: s.dayXpGained || 0,
    xpDay: s.xpDay || '',
    priorities: s.priorities,
    focusChips: s.focusChips,
    focusLog: (s.focusLog || []).slice(-100),
    currentFocus: s.currentFocus,
    planItems: s.planItems,
    planRestDays: s.planRestDays,
    planSchedules: s.planSchedules || {},
    planDayZones: s.planDayZones || {},
    planDayOff: s.planDayOff || {},
    planRules: s.planRules || [],
    planDayLog: s.planDayLog || {},
    rareEvents: s.rareEvents || [],
    dayTemplates: s.dayTemplates || [],
    weekTemplates: s.weekTemplates || [],
    weekTplSeeded: !!s.weekTplSeeded,
    notepad: s.notepad || '',
    theme: s.theme || 'dark',
    saveDate: new Date().toDateString(),
  };
}

/**
 * applyState() — рядки 4308–4348. Кожне поле перевіряється за ТИМ САМИМ
 * типом, що й в оригіналі (Array.isArray, а не .length!) — інакше порожній
 * масив після імпорту не збережеться, а «воскресить» старі дані, як і
 * застерігав коментар в оригіналі.
 */
export function applyState(raw: unknown): boolean {
  if (!raw || typeof raw !== 'object') return false;
  const d = raw as Partial<AppState>;
  const patch: Partial<AppState> = {};

  if (Array.isArray(d.tasks)) patch.tasks = d.tasks;
  if (Array.isArray(d.recur)) patch.recur = d.recur;
  if (Array.isArray(d.zones)) patch.zones = d.zones;
  if (Array.isArray(d.folders)) patch.folders = d.folders;
  if (Array.isArray(d.qnotes)) patch.qnotes = d.qnotes;
  if (typeof d.xp === 'number') patch.xp = d.xp;
  if (typeof d.xpTotal === 'number') patch.xpTotal = d.xpTotal;
  if (typeof d.level === 'number') patch.level = d.level;
  if (typeof d.done === 'number') patch.done = d.done;
  if (typeof d.streak === 'number') patch.streak = d.streak;
  if (typeof d.negCount === 'number') patch.negCount = d.negCount;
  if (typeof d.nid === 'number') patch.nid = d.nid;
  if (Array.isArray(d.unlocked)) patch.unlocked = d.unlocked;
  if (typeof d.energy === 'number') patch.energy = d.energy;
  if (typeof d.lastEnergyXp === 'number') patch.lastEnergyXp = d.lastEnergyXp;
  if (typeof d.dayXpGained === 'number') patch.dayXpGained = d.dayXpGained;
  if (typeof d.xpDay === 'string') patch.xpDay = d.xpDay;
  if (Array.isArray(d.priorities)) patch.priorities = d.priorities;
  if (Array.isArray(d.focusChips) && d.focusChips.length) patch.focusChips = d.focusChips;
  if (Array.isArray(d.focusLog)) patch.focusLog = d.focusLog;
  if (typeof d.currentFocus === 'string') patch.currentFocus = d.currentFocus;
  if (typeof d.notepad === 'string') patch.notepad = d.notepad;
  if (d.theme === 'light' || d.theme === 'dark') patch.theme = d.theme;
  if (Array.isArray(d.rareEvents)) patch.rareEvents = d.rareEvents;
  if (Array.isArray(d.dayTemplates)) patch.dayTemplates = d.dayTemplates;
  if (Array.isArray(d.weekTemplates)) patch.weekTemplates = d.weekTemplates;
  if (typeof d.weekTplSeeded === 'boolean') patch.weekTplSeeded = d.weekTplSeeded;
  if (d.planItems && typeof d.planItems === 'object') patch.planItems = d.planItems;
  if (d.planRestDays && typeof d.planRestDays === 'object') patch.planRestDays = d.planRestDays;
  if (d.planSchedules && typeof d.planSchedules === 'object') patch.planSchedules = d.planSchedules;
  if (d.planDayZones && typeof d.planDayZones === 'object') patch.planDayZones = d.planDayZones;
  if (d.planDayOff && typeof d.planDayOff === 'object') patch.planDayOff = d.planDayOff;
  if (Array.isArray(d.planRules)) patch.planRules = d.planRules;
  if (d.planDayLog && typeof d.planDayLog === 'object') patch.planDayLog = d.planDayLog;

  useStore.setState(patch);

  // Нормалізуємо лічильники id, щоб нові задачі/нотатки не конфліктували
  // (рядки 4345–4346, 4438–4440)
  const s = useStore.getState();
  let nid = s.nid;
  s.tasks.forEach((t) => {
    if (t.id >= nid) nid = t.id + 1;
  });
  const qnid = s.qnotes.length ? Math.max(...s.qnotes.map((n) => n.id || 0)) + 1 : 100;
  useStore.setState({ nid, qnid });

  return true;
}

// buildForegroundNotifPayload() тепер повністю реалізовано — bridge/notifPayload.ts (fgNotifText() перенесено).

let saveDebounceHandle: ReturnType<typeof setTimeout> | null = null;

/** saveState() — рядки 4291–4302 */
export function saveState(): void {
  try {
    const json = JSON.stringify(collectState());
    localStorage.setItem(LS_KEY, json);
    if (flowBridge()) {
      flowBridge()!.save(json);
      try {
        flowBridge()!.saveNotif(JSON.stringify(buildForegroundNotifPayload()));
      } catch {
        /* ігноруємо — не критично для збереження стану */
      }
      try {
        flowBridge()!.writeBackup(json);
      } catch {
        /* резервна копія не критична для основного збереження */
      }
    }
  } catch (e) {
    console.log('FLOW save error:', e);
  }
}

/**
 * loadState() — ВАЖЛИВЕ УТОЧНЕННЯ: в оригіналі стан завантажується ДВІЧІ при
 * старті: (1) рання IIFE, рядки 2930–2943, яка читає window.FlowBridge.load()
 * ПЕРШИМ і localStorage як резерв — саме вона фактично визначає, які дані
 * побачить користувач, бо виконується ДО будь-яких рендерів; (2) окрема
 * названа функція loadState(), рядки 4350–4358, localStorage-only,
 * викликана ЩЕ РАЗ пізніше (рядок 4436). Це, найімовірніше, історичний
 * дублікат (saveState() завжди пише в обидва сховища одночасно, тож у
 * нормальному режимі вони ідентичні) — повторний виклик нешкідливий, але
 * зайвий. Тут перенесено ПОВЕДІНКУ раннього завантаження (FlowBridge-first,
 * бо саме вона головна) ОДНИМ викликом замість буквального дублювання.
 * Повертає saveDate завантажених даних для dailyResetIfNeeded().
 */
export function loadState(): string | null {
  try {
    const raw = (flowBridge() ? flowBridge()!.load() : null) || localStorage.getItem(LS_KEY);
    if (!raw) return null;
    const d = JSON.parse(raw);
    if (d && applyState(d)) return d.saveDate || '';
    return null;
  } catch (e) {
    console.log('FLOW load error:', e);
    return null;
  }
}

/** queueSave() — рядки 4429–4432 */
export function queueSave(): void {
  if (saveDebounceHandle) clearTimeout(saveDebounceHandle);
  saveDebounceHandle = setTimeout(saveState, 1500);
}

/**
 * BACKUP CHECK — рядки 2967-2991. Викликати ОДИН РАЗ при старті, ПІСЛЯ
 * loadState(). Повертає дані для BackupBanner.tsx замість напряму будувати
 * DOM (showBackupBanner() у оригіналі це робив прямо тут).
 */
export type BackupCheckResult =
  | { kind: 'none' }
  | { kind: 'valid'; data: unknown; taskCount: number; saveDate: string; raw: string }
  | { kind: 'unreadable'; raw: string };

export function checkBackupRecovery(): BackupCheckResult {
  const fb = flowBridge();
  if (!fb) return { kind: 'none' };
  try {
    const hasLocal = !!localStorage.getItem(LS_KEY);
    const hasBk = fb.hasBackup();
    if (!hasBk) return { kind: 'none' };
    if (hasLocal) return { kind: 'none' }; // є обидва — нічого не питаємо, тихо оновиться при наступному save
    const raw = fb.readBackup();
    if (!raw) return { kind: 'none' };
    let d: { tasks?: unknown; saveDate?: string } | null = null;
    try {
      d = JSON.parse(raw);
    } catch {
      d = null;
    }
    if (d && d.tasks) {
      const taskCount = Array.isArray(d.tasks) ? (d.tasks as { trashed?: boolean }[]).filter((t) => !t.trashed).length : 0;
      return { kind: 'valid', data: d, taskCount, saveDate: d.saveDate || '?', raw };
    }
    if (raw.length > 0) return { kind: 'unreadable', raw };
    return { kind: 'none' };
  } catch (e) {
    console.log('FLOW backup check:', e);
    return { kind: 'none' };
  }
}
