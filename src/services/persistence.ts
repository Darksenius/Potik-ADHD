import { validateBackup } from './backupValidation';
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
    focusLog: (s.focusLog || []),
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
    preferences: s.preferences,
    saveDate: new Date().toDateString(),
  };
}

/**
 * applyState() — рядки 4308–4348. Кожне поле перевіряється за ТИМ САМИМ
 * типом, що й в оригіналі (Array.isArray, а не .length!) — інакше порожній
 * масив після імпорту не збережеться, а «воскресить» старі дані, як і
 * застерігав коментар в оригіналі.
 */
/** Validation completes before a single atomic store update. */
export function applyState(raw: unknown, replace = false): boolean {
  try {
    const parsed = validateBackup(raw);
    const patch: Partial<AppState> = {};
    if (replace) {
      const defaults = useStore.getInitialState();
      for (const key of Object.keys(collectState())) {
        if (key !== 'saveDate') (patch as Record<string, unknown>)[key] = defaults[key as keyof typeof defaults];
      }
    }
    Object.assign(patch, parsed);
    delete patch.saveDate;
    const current = useStore.getState();
    const tasks = patch.tasks || current.tasks;
    const qnotes = patch.qnotes || current.qnotes;
    const nid = Math.max(patch.nid || current.nid, 1, ...tasks.map(t => t.id + 1));
    const qnid = Math.max(100, ...qnotes.map(n => n.id + 1));
    useStore.setState({ ...patch, nid, qnid });
    return true;
  } catch { return false; }
}

let saveDebounceHandle: ReturnType<typeof setTimeout> | null = null;
let saveBlocked = false;
let loadedSuccessfully = false;
const recoverySources: { source: string; raw: string }[] = [];
export function releaseSaveGuard(): void { saveBlocked = false; }
export function saveIsBlocked(): boolean { return saveBlocked; }
export function getRecoverySources(): string { return JSON.stringify(recoverySources, null, 2); }

/** Room is authoritative on Android. Only mirror a confirmed native commit;
 * otherwise an older valid Room snapshot would replace newer browser data on
 * the next launch. A browser quota failure cannot invalidate a Room commit. */
export function saveState(eventIds?: number[]): boolean {
  if (saveBlocked || useStore.getState().backupBanner) return false;
  let json: string;
  try { json = JSON.stringify(collectState()); } catch { return false; }
  const fb = flowBridge();
  let nativeSaved = false;
  let localSaved = false;
  const nativeSaveFailed = () => {
    useStore.getState().showToast('Не вдалося зберегти дані в Android. Зміни ще не збережено: повтори спробу або зроби експорт, перш ніж закривати застосунок.');
    return false;
  };
  try {
    if (eventIds) {
      if (!fb?.saveWithEvents || !fb.saveWithEvents(json, JSON.stringify(eventIds))) return nativeSaveFailed();
      nativeSaved = true;
    } else if (fb) {
      // The current bridge returns an explicit success value even when Java
      // cannot commit. Empty ids acknowledge nothing and preserve queued events.
      if (fb.saveWithEvents) {
        if (!fb.saveWithEvents(json, '[]')) return nativeSaveFailed();
      } else fb.save(json);
      nativeSaved = true;
    }
  } catch { return nativeSaveFailed(); }
  try { localStorage.setItem(LS_KEY, json); localSaved = true; } catch { /* Native saving still works when browser quota is exhausted. */ }
  if (fb && nativeSaved) {
    try { fb.saveNotif(JSON.stringify(buildForegroundNotifPayload())); } catch { /* Snapshot can be refreshed later. */ }
    try { fb.writeBackup(json); } catch { /* Does not invalidate Room state. */ }
  }
  if (!nativeSaved && !localSaved) useStore.getState().showToast('Не вдалося зберегти дані. Зроби експорт, перш ніж закривати застосунок.');
  return nativeSaved || localSaved;
}

export function loadState(): string | null {
  loadedSuccessfully = false;
  const readers: [string, () => string | null | undefined][] = [['Android', () => flowBridge()?.load()], ['Браузер', () => localStorage.getItem(LS_KEY)]];
  let failed = false;
  for (const [source, read] of readers) {
    try {
      const raw = read();
      if (!raw) continue;
      let parsed: Partial<AppState>;
      try { parsed = validateBackup(JSON.parse(raw)); } catch { failed = true; recoverySources.push({ source, raw }); continue; }
      if (applyState(parsed)) { loadedSuccessfully = true; saveBlocked = false; return parsed.saveDate || ''; }
    } catch { failed = true; }
  }
  saveBlocked = failed;
  return null;
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
  if (loadedSuccessfully) return { kind: 'none' };
  const fb = flowBridge();
  if (fb) {
    try {
      const raw = fb.readBackup();
      if (raw) {
        try {
          const d = validateBackup(JSON.parse(raw));
          saveBlocked = true;
          return { kind: 'valid', data: d, taskCount: (d.tasks || []).filter(t => !t.trashed).length, saveDate: d.saveDate || '?', raw };
        } catch { recoverySources.push({ source: 'Резервний файл', raw }); saveBlocked = true; }
      }
    } catch { /* Preserve earlier failure state. */ }
  }
  return saveBlocked ? { kind: 'unreadable', raw: getRecoverySources() } : { kind: 'none' };
}
