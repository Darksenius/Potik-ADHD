import { useEffect, useState, useSyncExternalStore } from 'react';
import { getNotificationDiagnostics, openExactAlarmSettings, openBatterySettings, scheduleDiagnosticReminder, openNativeNotificationSettings } from '../../bridge/nativeBridge';
import type { NotificationDiagnostics as Diagnostics } from '../../bridge/nativeBridge';
import { getSaveStatus, subscribeSaveStatus } from '../../services/saveStatus';
import ClipboardFallback from '../common/ClipboardFallback';
import '../../styles/notification-diagnostics.css';

export default function NotificationDiagnostics() {
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [receipt, setReceipt] = useState('');
  const [busy, setBusy] = useState(false);
  const [copyText, setCopyText] = useState<string | null>(null);
  const saveStatus = useSyncExternalStore(subscribeSaveStatus, getSaveStatus, getSaveStatus);
  async function refresh() {
    setLoading(true);
    try { setDiagnostics(await getNotificationDiagnostics()); setError(''); }
    catch { setError('Не вдалося прочитати стан Android. Повтори оновлення.'); }
    finally { setLoading(false); }
  }
  useEffect(() => { void refresh(); const onFocus = () => { void refresh(); }; window.addEventListener('focus', onFocus); return () => window.removeEventListener('focus', onFocus); }, []);
  async function perform(action: () => Promise<void>) {
    try { await action(); } catch { setError('Не вдалося відкрити налаштування. Знайди Потік у налаштуваннях Android.'); }
  }
  async function testSignal() {
    setBusy(true); setReceipt(''); setError('');
    try {
      const result = await scheduleDiagnosticReminder();
      if (!result.scheduled || !result.firesAt) throw new Error();
      setReceipt('Пробний сигнал заплановано приблизно на ' + new Date(result.firesAt).toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' }) + '. Можеш згорнути Потік.');
    } catch { setError('Не вдалося запланувати пробний сигнал. Перевір дозволи сповіщень і нагадувань.'); }
    finally { setBusy(false); }
  }
  async function copyDiagnostics() {
    const text = JSON.stringify({ app: 'Потік', ...diagnostics, saveStatus: saveStatus.phase, checkedAt: new Date().toISOString() }, null, 2);
    try { if (!navigator.clipboard) throw new Error(); await navigator.clipboard.writeText(text); setReceipt('Діагностику скопійовано. Текстів задач і нотаток у ній немає.'); }
    catch { setCopyText(text); }
  }
  const channel = (value: boolean | null | undefined) => value == null ? 'Ще не створено або стан невідомий' : value ? 'Дозволено' : 'Вимкнено в Android';
  return <details className="notification-diagnostics">
    <summary>Чи працюють нагадування?</summary>
    <div className="notification-diagnostics-body">
      {loading && <p role="status">Перевіряю стан…</p>}
      {!loading && !diagnostics && !error && <p>Повна перевірка доступна у встановленому Android-застосунку.</p>}
      {diagnostics && <>
        <dl>
          <div><dt>Сповіщення застосунку</dt><dd>{diagnostics.notificationsEnabled ? 'Дозволено' : 'Вимкнено'}</dd></div>
          <div><dt>Сигнали задач</dt><dd>{channel(diagnostics.reminderChannelEnabled)}</dd></div>
          <div><dt>Постійна панель</dt><dd>{channel(diagnostics.panelChannelEnabled)}</dd></div>
          <div><dt>Картки квестів</dt><dd>{channel(diagnostics.quickActionsChannelEnabled)}</dd></div>
          <div><dt>Точні нагадування</dt><dd>{diagnostics.exactAlarmsAllowed ? 'Доступні' : 'Потрібен дозвіл Android'}</dd></div>
          <div><dt>Оптимізація батареї</dt><dd>{diagnostics.batteryOptimizationExempt ? 'Для Потоку вимкнена' : 'Керується Android'}</dd></div>
          <div><dt>Найближчий запланований сигнал</dt><dd>{diagnostics.nextReminderAt ? new Date(diagnostics.nextReminderAt).toLocaleString('uk') : 'Зараз немає запланованих сигналів'}</dd></div>
          <div><dt>Збереження</dt><dd>{saveStatus.phase === 'error' ? saveStatus.message : diagnostics.lastSaveError ? 'Остання помилка: ' + diagnostics.lastSaveError : saveStatus.phase === 'pending' ? 'Зберігаю зміни…' : 'Останній запис успішний'}</dd></div>
        </dl>
        <div className="diagnostic-actions">
          {(!diagnostics.notificationsEnabled || diagnostics.reminderChannelEnabled === false || diagnostics.panelChannelEnabled === false || diagnostics.quickActionsChannelEnabled === false) && <button type="button" onClick={() => { void perform(openNativeNotificationSettings); }}>Дозволи сповіщень</button>}
          {!diagnostics.exactAlarmsAllowed && diagnostics.androidVersion >= 31 && <button type="button" onClick={() => { void perform(openExactAlarmSettings); }}>Дозволити точні нагадування</button>}
          {!diagnostics.batteryOptimizationExempt && <button type="button" onClick={() => { void perform(openBatterySettings); }}>Налаштування батареї</button>}
          <button type="button" disabled={busy || !diagnostics.notificationsEnabled || diagnostics.reminderChannelEnabled === false} onClick={() => { void testSignal(); }}>Пробний сигнал через хвилину</button>
          <button type="button" onClick={() => { void copyDiagnostics(); }}>Копіювати діагностику</button>
        </div>
        <p>Стан дозволів не підтверджує доставку сигналу. Пробний сигнал допоможе перевірити її на цьому телефоні.</p>
      </>}
      <button type="button" disabled={loading} onClick={() => { void refresh(); }}>Оновити стан</button>
      {receipt && <p role="status">{receipt}</p>}{error && <p role="alert">{error}</p>}
    </div>
    {copyText && <ClipboardFallback text={copyText} onClose={() => setCopyText(null)} />}
  </details>;
}
