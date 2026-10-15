import { validateBackup } from './backupValidation';
import { useStore } from '../state/store';
import type { AppState } from '../types';
import { flowBridge } from '../bridge/nativeBridge';
import { buildForegroundNotifPayload } from '../bridge/notifPayload';
import { seedKnownCompletions } from '../utils/taskHistory';
import { normalizePlannerTasks } from './plannerMigration';
import { normalizeRoutineTasks } from './routineMigration';
import { markSaveError, markSaveSaved } from './saveStatus';

/** Stable storage slot. formatVersion distinguishes the persisted domain schema. */
export const LS_KEY = 'flow_v2';

/**
 * Persist only domain data. Add persisted fields here and to backupValidation;
 * autosave also uses these keys to distinguish edits from transient UI changes.
 */
export function collectState(): AppState {
  const s = useStore.getState();
  return {
    formatVersion: 2,
    tasks: s.tasks,
    taskHistory: seedKnownCompletions(s.taskHistory, s.tasks),
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
    notepadFolder: s.notepadFolder,
    taskEditorDraft: s.taskEditorDraft,
    theme: s.theme || 'dark',
    preferences: s.preferences,
    saveDate: new Date().toDateString(),
  };
}

/**
 * Validate and normalize before one atomic update. Empty arrays intentionally
 * replace their collections, and replace=true begins from current schema defaults.
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
    const normalized = normalizePlannerTasks(patch.tasks || current.tasks, patch.nid || current.nid,
      patch.planItems || current.planItems);
    Object.assign(patch, normalized);
    let tasks = normalized.tasks;
    let nid = Math.max(patch.nid || current.nid, 1, ...tasks.map(t => t.id + 1));
    if (Array.isArray(patch.recur) && patch.recur.length) {
      const migrated = normalizeRoutineTasks(tasks, patch.recur, nid, parsed.saveDate);
      tasks = migrated.tasks;
      nid = migrated.nid;
      patch.recur = [];
    }
    patch.tasks = tasks;
    patch.taskHistory = seedKnownCompletions(patch.taskHistory || current.taskHistory, tasks);
    const qnotes = patch.qnotes || current.qnotes;
    const qnid = Math.max(100, ...qnotes.map(n => n.id + 1));
    useStore.setState({ ...patch, nid, qnid });
    return true;
  } catch { return false; }
}

let saveDebounceHandle: ReturnType<typeof setTimeout> | null = null;
let saveBlocked = false;
let loadedSuccessfully = false;
let recoveryReadFailed = false;
let lastSaveFailure: 'recovery' | 'native' | 'storage_full' | 'serialize' | 'browser' | null = null;
const recoverySources: { source: string; raw: string }[] = [];
export const RECOVERY_ARCHIVE_KEY = 'flow_v2_recovery_archive';
export function releaseSaveGuard(): void { saveBlocked = false; lastSaveFailure = null; }
export function blockSavingForRecovery(): void { saveBlocked = true; }
export function saveIsBlocked(): boolean { return saveBlocked; }
export function getRecoverySources(): string { return JSON.stringify(recoverySources, null, 2); }

function rememberRecovery(source: string, raw: string): void {
  if (!recoverySources.some(item => item.source === source && item.raw === raw)) recoverySources.push({ source, raw });
}

export function getSaveErrorMessage(): string {
  if (lastSaveFailure === 'recovery' || saveBlocked || useStore.getState().backupBanner) {
    return 'Не вдалося автоматично архівувати старі дані або записати нові. Оригінали збережено. Відкрий архівацію, щоб повторити спробу або зберегти оригінали у файл.';
  }
  if (lastSaveFailure === 'storage_full') return 'Android повідомив, що немає місця для запису. Звільни місце й повтори збереження. Зміни ще лише в пам’яті; їх можна експортувати.';
  if (lastSaveFailure === 'native') return 'Android не зміг записати дані. Повтори збереження або зроби експорт перед закриттям застосунку.';
  if (lastSaveFailure === 'serialize') return 'Не вдалося підготувати дані для запису. Зміни ще лише в пам’яті; зроби експорт перед закриттям застосунку.';
  return 'Не вдалося записати дані у сховище браузера. Зміни ще лише в пам’яті; повтори спробу або зроби експорт.';
}

export function showRecoveryBanner(): void {
  if (!saveBlocked) return;
  useStore.getState().setBackupBanner('unreadable');
}

/** Archive originals before allowing the current workspace to replace them. */
export function continueWithCurrentState(quiet = false): boolean {
  if (!saveBlocked && !useStore.getState().backupBanner) return true;
  const fail = () => {
    useStore.getState().showToast('Не вдалося зберегти стару копію окремо. Оригінали не змінено. Повтори архівацію або збережи оригінали у файл.');
    return false;
  };
  if (recoveryReadFailed) return fail(); // Unknown bytes cannot be safely replaced.
  const archive = JSON.stringify({ savedAt: new Date().toISOString(), sources: recoverySources });
  const fb = flowBridge();
  try {
    if (fb) {
      if (!fb.archiveRecovery?.(archive)) return fail();
    } else {
      // Keep prior archives when the user recovers more than once.
      const previous = localStorage.getItem(RECOVERY_ARCHIVE_KEY);
      const value = previous ? { ...JSON.parse(archive), previous } : JSON.parse(archive);
      localStorage.setItem(RECOVERY_ARCHIVE_KEY, JSON.stringify(value));
    }
  } catch { return fail(); }
  const banner = useStore.getState().backupBanner;
  releaseSaveGuard();
  useStore.getState().dismissBackupBanner();
  if (!saveState()) {
    saveBlocked = true;
    useStore.getState().setBackupBanner(banner);
    return false;
  }
  loadedSuccessfully = true;
  if (!quiet) useStore.getState().showToast('Старі дані заархівовано. Збереження працює.');
  return true;
}

export interface RecoveryArchive { name: string; createdAt: number; size: number; }

export function listRecoveryArchives(): RecoveryArchive[] {
  try {
    const fb = flowBridge();
    if (fb) {
      const entries: unknown = JSON.parse(fb.listRecoveryArchives?.() || '[]');
      if (!Array.isArray(entries)) return [];
      return entries.filter((item): item is RecoveryArchive => !!item && typeof item.name === 'string'
        && Number.isFinite(item.createdAt) && Number.isFinite(item.size));
    }
    const raw = localStorage.getItem(RECOVERY_ARCHIVE_KEY);
    if (!raw) return [];
    let createdAt = 0;
    try { createdAt = Date.parse(JSON.parse(raw).savedAt) || 0; } catch { /* An unreadable archive can still be exported. */ }
    return [{ name: 'flow-recovery-archive.json', createdAt, size: new Blob([raw]).size }];
  } catch { return []; }
}

export function readRecoveryArchive(name?: string): string {
  try {
    const fb = flowBridge();
    if (fb) return (name ? fb.readRecoveryArchiveFile?.(name) : fb.readRecoveryArchive?.()) || '';
    return localStorage.getItem(RECOVERY_ARCHIVE_KEY) || '';
  }
  catch { return ''; }
}

/** Room is authoritative on Android. Only mirror a confirmed native commit;
 * otherwise an older valid Room snapshot would replace newer browser data on
 * the next launch. A browser quota failure cannot invalidate a Room commit. */
export function saveState(eventIds?: number[]): boolean {
  lastSaveFailure = null;
  if (saveBlocked || useStore.getState().backupBanner) {
    lastSaveFailure = 'recovery'; markSaveError(getSaveErrorMessage()); return false;
  }
  let json: string;
  try { json = JSON.stringify(collectState()); } catch {
    lastSaveFailure = 'serialize'; markSaveError(getSaveErrorMessage()); return false;
  }
  const fb = flowBridge();
  let nativeSaved = false;
  let localSaved = false;
  const nativeSaveFailed = () => {
    lastSaveFailure = 'native';
    try { if (fb?.getLastSaveError?.() === 'storage_full') lastSaveFailure = 'storage_full'; } catch { /* Keep the generic write error. */ }
    useStore.getState().showToast('Не вдалося зберегти дані в Android. Зміни ще не збережено: повтори спробу або зроби експорт, перш ніж закривати застосунок.');
    markSaveError(getSaveErrorMessage());
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
  if (!nativeSaved && !localSaved) {
    lastSaveFailure = 'browser';
    useStore.getState().showToast(getSaveErrorMessage());
    markSaveError(getSaveErrorMessage());
    return false;
  }
  markSaveSaved();
  return true;
}

export function loadState(): string | null {
  loadedSuccessfully = false;
  recoveryReadFailed = false;
  recoverySources.length = 0;
  const readers: [string, () => string | null | undefined][] = [['Android', () => flowBridge()?.load()], ['Браузер', () => localStorage.getItem(LS_KEY)]];
  let failed = false;
  for (const [source, read] of readers) {
    try {
      const raw = read();
      if (!raw) continue;
      let parsed: Partial<AppState>;
      try { parsed = validateBackup(JSON.parse(raw)); } catch { failed = true; rememberRecovery(source, raw); continue; }
      if (applyState(parsed)) { loadedSuccessfully = true; saveBlocked = failed; return parsed.saveDate || ''; }
    } catch { failed = true; recoveryReadFailed = true; }
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
  if (loadedSuccessfully && !saveBlocked) return { kind: 'none' };
  const fb = flowBridge();
  if (fb) {
    try {
      const raw = fb.readBackup();
      if (raw) {
        try {
          const d = validateBackup(JSON.parse(raw));
          saveBlocked = true;
          rememberRecovery('Резервний файл', raw);
          return { kind: 'valid', data: d, taskCount: (d.tasks || []).filter(t => !t.trashed).length, saveDate: d.saveDate || '?', raw };
        } catch { rememberRecovery('Резервний файл', raw); saveBlocked = true; }
      }
    } catch { recoveryReadFailed = true; saveBlocked = true; }
  }
  return saveBlocked ? { kind: 'unreadable', raw: getRecoverySources() } : { kind: 'none' };
}
