import { chooseBackupFolder, confirmBackupReplacement, flowBridge, flushNativeBackup } from '../bridge/nativeBridge';
import { flushAutosave } from './autosave';
import { checkBackupRecovery, collectState, getSaveErrorMessage } from './persistence';
import { importState } from './exportImport';

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
