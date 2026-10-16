import { chooseBackupFolder, confirmBackupReplacement, flowBridge, flushNativeBackup } from '../bridge/nativeBridge';
import { flushAutosave } from './autosave';
import { checkBackupRecovery, collectState, getSaveErrorMessage } from './persistence';
import { importState } from './exportImport';
import { validateBackup } from './backupValidation';
import { saveIsBlocked } from './persistence';

function sameBackup(left: unknown, right: unknown): boolean {
  const canonical = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(canonical);
    if (!value || typeof value !== 'object') return value;
    const result: Record<string, unknown> = {};
    Object.keys(value).sort().forEach(key => { result[key] = canonical((value as Record<string, unknown>)[key]); });
    return result;
  };
  try {
    const a = validateBackup(typeof left === 'string' ? JSON.parse(left) : left); const b = validateBackup(right);
    delete a.saveDate; delete b.saveDate;
    return JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));
  } catch { return false; }
}

/** The picker never grants permission to overwrite an existing user backup. */
export async function configureBackupFolder(): Promise<string | undefined> {
  if (!flushAutosave()) throw new Error(getSaveErrorMessage());
  const selected = await chooseBackupFolder('backup');
  if (selected.cancelled) return;
  if (selected.hasBackup && !window.confirm('У цій папці вже є резервна копія. Замінити її поточними даними? Щоб повернути дані з неї, скасуй заміну й обери «Відновити з папки резервних копій».')) {
    return 'Копію не змінено. Для відновлення скористайся окремою кнопкою.';
  }
  const status = selected.hasBackup ? await confirmBackupReplacement() : await flushNativeBackup();
  if (status?.lastError) throw new Error(status.lastError);
  return 'Папку резервних копій обрано. Поточні дані скопійовано.';
}

/** Validate first, preserve the previous workspace, then durably restore. */
export async function restoreFromBackupFolder(): Promise<string | undefined> {
  const selected = await chooseBackupFolder('restore');
  if (selected.cancelled) return;
  if (!selected.raw) throw new Error('У цій папці немає резервної копії Потоку.');
  const checked = checkBackupRecovery(selected.raw);
  if (checked.kind !== 'valid') throw new Error('Резервна копія пошкоджена або має несумісний формат. Поточні дані не змінено.');
  if (!window.confirm(`Відновити дані з цієї папки (${checked.taskCount} задач)? Поточний стан буде збережено в архіві перед заміною.`)) return;
  const bridge = flowBridge();
  // An identical, already committed restore needs neither a new archive nor
  // another import. Check both the live workspace and Room, not just the file.
  if (!saveIsBlocked() && sameBackup(collectState(), checked.data)
      && sameBackup(bridge?.load() || 'null', checked.data)) {
    const status = await confirmBackupReplacement();
    return status.lastError ? `Дані вже є в Потоку. ${status.lastError}` : 'Ці дані вже є в Потоку. Резервне копіювання підключено.';
  }
  const before = JSON.stringify({ savedAt: new Date().toISOString(), sources: [{ source: 'Перед відновленням з папки', raw: JSON.stringify(collectState()) }] });
  if (!bridge?.archiveRecovery?.(before)) throw new Error('Не вдалося заархівувати поточні дані. Відновлення не виконано.');
  importState(checked.data, 'restore');
  try {
    const status = await confirmBackupReplacement();
    return status.lastError
      ? `Дані відновлено в застосунку. Резервна копія ще не оновлена: ${status.lastError}`
      : 'Дані відновлено з папки. Резервне копіювання підключено.';
  } catch (error) {
    const detail = error instanceof Error ? error.message : 'Перевір стан папки в налаштуваннях.';
    return `Дані відновлено в застосунку. Не вдалося підтвердити запис резервної копії: ${detail}`;
  }
}
