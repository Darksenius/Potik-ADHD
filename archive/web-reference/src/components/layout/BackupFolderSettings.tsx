import { useEffect, useState } from 'react';
import { getBackupFolderStatus, isCapacitor, type BackupFolderStatus } from '../../bridge/nativeBridge';
import { configureBackupFolder, restoreFromBackupFolder } from '../../services/backupFolder';
import { useStore } from '../../state/store';

export default function BackupFolderSettings() {
  const [status, setStatus] = useState<BackupFolderStatus>();
  const [busy, setBusy] = useState(false);
  const native = isCapacitor();
  const refresh = () => { void getBackupFolderStatus().then(setStatus).catch(() => setStatus({ configured: false, folderName: '', lastSuccessfulWrite: 0, lastError: 'Не вдалося перевірити папку.' })); };
  useEffect(() => {
    if (!native) return;
    refresh();
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [native]);
  if (!native) return null;
  const run = async (action: () => Promise<string | undefined>) => {
    setBusy(true);
    try { const message = await action(); if (message) useStore.getState().showToast(message); }
    catch (error) { useStore.getState().showToast(error instanceof Error ? error.message : 'Не вдалося працювати з резервною копією.'); }
    finally { setBusy(false); refresh(); }
  };
  return <section aria-label="Папка резервних копій">
    <button type="button" disabled={busy} onClick={() => void run(configureBackupFolder)}>Обрати папку резервних копій</button>
    <button type="button" disabled={busy} onClick={() => void run(restoreFromBackupFolder)}>Відновити з папки резервних копій</button>
    <p className="settings-note">Щоб копія залишилася після видалення Потоку, обери папку поза даними застосунку.</p>
    <p className="settings-note">Папка: {status?.configured ? status.folderName : 'не обрана'}. Після перевстановлення обери її знову для відновлення.</p>
    {status?.lastSuccessfulWrite ? <p className="settings-note">Остання копія: {new Date(status.lastSuccessfulWrite).toLocaleString('uk-UA')}</p> : null}
    {status?.lastError && <p role="status" className="settings-note">{status.lastError}</p>}
  </section>;
}
