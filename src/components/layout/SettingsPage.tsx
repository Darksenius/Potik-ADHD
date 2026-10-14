import { useEffect, useState } from 'react';
import { useStore } from '../../state/store';
import OssPage, { useAppVersion } from '../oss/OssPage';
import ExportImportSection from '../notes/ExportImportSection';
import { requestAndShowNotification } from '../../services/webNotifications';
import { listRecoveryArchives, saveIsBlocked, showRecoveryBanner } from '../../services/persistence';
import type { RecoveryArchive } from '../../services/persistence';
import { exportRecoveryFile } from '../../services/exportImport';
import { getNativeNotificationPermission, requestNativeNotificationPermission, openNativeNotificationSettings, updateForegroundService } from '../../bridge/nativeBridge';
import '../../styles/compact-settings.css';
import '../../styles/whats-new.css';

export default function SettingsPage() {
  const preferences = useStore(s => s.preferences);
  const setPreferences = useStore(s => s.setPreferences);
  const [ossOpen, setOssOpen] = useState(false);
  const version = useAppVersion();
  const [permission, setPermission] = useState('unavailable');
  const [recoveryArchives, setRecoveryArchives] = useState<RecoveryArchive[]>([]);
  useEffect(() => {
    const refresh = () => { void getNativeNotificationPermission().then(setPermission); };
    refresh(); window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);
  useEffect(() => {
    const refresh = () => {
      try { setRecoveryArchives(listRecoveryArchives()); }
      catch { setRecoveryArchives([]); }
    };
    refresh(); window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);
  return <><main id="settings-page" className="page active settings-page">
    <h1>Налаштування</h1>
    <p className="settings-intro">Параметри застосунку, дані та довідка.</p>
    <button type="button" className="settings-whats-new" aria-label="Що нового" onClick={() => useStore.getState().showPage('whatsnew')}>
      <strong>Що нового</strong><small>{version} · Для тебе</small>
    </button>

    {saveIsBlocked() && <div className="settings-storage-alert" role="alert">
      <p>Не вдалося автоматично архівувати старі дані. Оригінали збережено; повтори архівацію або експортуй копію.</p>
      <button type="button" onClick={showRecoveryBanner}>Повторити архівацію</button>
    </div>}

    <details className="settings-group">
      <summary><span><strong>Вигляд</strong><small>Тема, анімації, досвід і мова</small></span></summary>
      <div className="settings-group-content">
        <label className="settings-field">Тема
          <select aria-label="Тема" className="ei" value={preferences.themeMode || 'system'} onChange={e => setPreferences({ themeMode: e.target.value as 'system' | 'light' | 'dark' })}>
            <option value="system">Як у системі</option><option value="dark">Темна</option><option value="light">Світла</option>
          </select>
        </label>
        <label className="editor-check"><input type="checkbox" checked={!!preferences.reduceMotion} onChange={e => setPreferences({ reduceMotion: e.target.checked })} /> Зменшити анімації</label>
        <label className="editor-check"><input type="checkbox" checked={preferences.showGamification !== false} onChange={e => setPreferences({ showGamification: e.target.checked })} /> Показувати досвід і рівень</label>
        <p className="settings-note"><strong>Мова інтерфейсу:</strong> українська. Це єдина доступна мова зараз.</p>
      </div>
    </details>

    <details className="settings-group">
      <summary><span><strong>Сповіщення</strong><small>Дозволи браузера та Android</small></span></summary>
      <div className="settings-group-content">
        <p>Для кожної запланованої задачі можна окремо вимкнути сигнал або додати нагадування заздалегідь.</p>
        {!window.Capacitor?.isNativePlatform?.() && <button type="button" onClick={requestAndShowNotification}>Перевірити сповіщення браузера</button>}
        {window.Capacitor?.isNativePlatform?.() && <>
          <p>Дозвіл Android: {permission === 'granted' ? 'надано' : permission === 'denied' ? 'вимкнено' : permission === 'prompt' ? 'ще не запитували' : 'не вдалося перевірити'}.</p>
          <button type="button" onClick={() => { void requestNativeNotificationPermission().then(state => { setPermission(state); if (state === 'granted') updateForegroundService(); }); }}>Дозволити сповіщення</button>
          <button type="button" onClick={openNativeNotificationSettings}>Відкрити налаштування Android</button>
        </>}
      </div>
    </details>

    <details className="settings-group">
      <summary><span><strong>Дані та резервні копії</strong><small>Експорт, імпорт і архів несумісних даних</small></span></summary>
      <div className="settings-group-content">
        <ExportImportSection />
        <section className="settings-archive" aria-labelledby="incompatible-archives-title">
          <h2 id="incompatible-archives-title">Архів несумісних даних</h2>
          <p>Тут зберігаються несумісні дані й старі резервні копії. Їх можна зберегти у файл; вони не додаються до поточних задач.</p>
          {recoveryArchives.length === 0
            ? <p>Архівів несумісних даних поки немає.</p>
            : <ul>{recoveryArchives.map(archive => {
              const created = new Date(archive.createdAt).toLocaleString('uk');
              const size = archive.size < 1024 ? `${archive.size} Б` : `${(archive.size / 1024).toFixed(1)} КБ`;
              return <li key={archive.name}>
                <span>{created} · {size}</span>
                <button type="button" onClick={() => exportRecoveryFile(true, archive.name)}>Експортувати архів</button>
              </li>;
            })}</ul>}
        </section>
      </div>
    </details>

    <details className="settings-group">
      <summary><span><strong>Про застосунок і допомога</strong><small>Довідка, версія та ліцензія</small></span></summary>
      <div className="settings-group-content">
        <p>Потік {version}</p>
        <button type="button" onClick={() => useStore.getState().showPage('readme')}>Відкрити довідку</button>
        <button type="button" onClick={() => setOssOpen(true)}>Ліцензія та вихідний код</button>
      </div>
    </details>
  </main><OssPage open={ossOpen} onClose={() => setOssOpen(false)} /></>;
}
