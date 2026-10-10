import { useEffect, useState } from 'react';
import { useStore } from '../../state/store';
import OssPage, { useAppVersion } from '../oss/OssPage';
import ExportImportSection from '../notes/ExportImportSection';
import { requestAndShowNotification } from '../../services/webNotifications';
import { getNativeNotificationPermission, requestNativeNotificationPermission, openNativeNotificationSettings, updateForegroundService } from '../../bridge/nativeBridge';

export default function SettingsPage() {
  const preferences = useStore(s => s.preferences);
  const setPreferences = useStore(s => s.setPreferences);
  const [ossOpen, setOssOpen] = useState(false);
  const version = useAppVersion();
  const [permission, setPermission] = useState('unavailable');
  useEffect(() => {
    const refresh = () => { void getNativeNotificationPermission().then(setPermission); };
    refresh(); window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, []);
  return <><main id="settings-page" className="page active settings-page">
    <h1>Налаштування</h1>
    <section><h2>Вигляд</h2><label className="el">Тема<select aria-label="Тема" className="ei" value={preferences.themeMode || 'system'} onChange={e => setPreferences({ themeMode: e.target.value as 'system' | 'light' | 'dark' })}><option value="system">Як у системі</option><option value="dark">Темна</option><option value="light">Світла</option></select></label>
      <label className="editor-check"><input type="checkbox" checked={!!preferences.reduceMotion} onChange={e => setPreferences({ reduceMotion: e.target.checked })} /> Зменшити анімації</label>
      <label className="editor-check"><input type="checkbox" checked={preferences.showGamification !== false} onChange={e => setPreferences({ showGamification: e.target.checked })} /> Показувати досвід і рівень</label>
    </section>
    <section><h2>Мова</h2><p>Українська. Повний англійський переклад заплановано в дорожній карті.</p></section>
    <section><h2>Нагадування</h2><p>Для кожної запланованої задачі можна окремо вимкнути сигнал або додати нагадування заздалегідь.</p>
      {!window.Capacitor?.isNativePlatform?.() && <button onClick={requestAndShowNotification}>Перевірити сповіщення браузера</button>}
      {window.Capacitor?.isNativePlatform?.() && <><p>Дозвіл Android: {permission === 'granted' ? 'надано' : permission === 'denied' ? 'вимкнено' : permission === 'prompt' ? 'ще не запитували' : 'не вдалося перевірити'}.</p><button onClick={() => { void requestNativeNotificationPermission().then(state => { setPermission(state); if (state === 'granted') updateForegroundService(); }); }}>Дозволити сповіщення</button><button onClick={openNativeNotificationSettings}>Відкрити налаштування Android</button></>}
    </section>
    <section><h2>Дані та резервні копії</h2><ExportImportSection /></section>
    <section><h2>Про застосунок</h2><p>Потік {version}</p><button onClick={() => setOssOpen(true)}>Ліцензія та вихідний код</button><button onClick={() => useStore.getState().showPage('readme')}>Відкрити довідку</button></section>
  </main><OssPage open={ossOpen} onClose={() => setOssOpen(false)} /></>;
}
