import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import App from './App';
import './styles/tokens.css';
import './styles/global.css';
import './styles/organizer.css';

import { useStore } from './state/store';
import { loadState, saveState, queueSave, checkBackupRecovery } from './services/persistence';
import { setupNativeBridge, updateForegroundService } from './bridge/nativeBridge';
import { checkNotificationPermissionOnBoot, refreshWebNotificationIfGranted } from './services/webNotifications';
import { fmtDate } from './utils/date';
import { startAutosave } from './services/autosave';
import { checkReminders } from './services/reminders';

/**
 * Bootstrap — відповідник "хвоста" www/index.html, рядки 2867–3062, 4434–4462.
 * Порядок навмисно той самий: спершу завантажити стан, ПОТІМ рендерити.
 */
function bootstrap() {
  useStore.setState({ planSelectedDay: fmtDate(new Date()) });

  // loadState() тепер повертає saveDate завантажених даних (або null, якщо
  // даних не було) — точний відповідник _loadedSaveDate з оригіналу.
  const loadedSaveDate = loadState();
  if (loadedSaveDate !== null) {
    useStore.getState().dailyResetIfNeeded(loadedSaveDate);
  }

  useStore.getState().resetRepeatingTasksFor(new Date());
  useStore.getState().recalculateZoneUsage();
  useStore.getState().seedWeekTemplates();
  useStore.getState().purgeOldTrash();
  checkNotificationPermissionOnBoot();

  // BACKUP CHECK — рядки 2967-2991
  const backupResult = checkBackupRecovery();
  if (backupResult.kind === 'unreadable') {
    const now = new Date();
    const timeStr = now.toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' });
    useStore.getState().addQuickNote('📦 Резервна копія (нечитабельна, ' + timeStr + '):\n' + backupResult.raw.slice(0, 500), 'impulse');
    queueSave();
    useStore.getState().setBackupBanner('unreadable');
  } else if (backupResult.kind === 'valid') {
    useStore.getState().setBackupBanner({ data: backupResult.data, taskCount: backupResult.taskCount, saveDate: backupResult.saveDate });
  }
  startAutosave();
  checkReminders();
  setInterval(checkReminders, 1000);

  // Хвилинний тік — рядки 3053-3061: щоденний скид + Android-сповіщення +
  // веб-сповіщення, якщо дозволено. (updateClock() тут не потрібен — час
  // реактивний через useClock у компонентах.)
  setInterval(() => {
    useStore.getState().checkDailyReset();
    updateForegroundService();
    refreshWebNotificationIfGranted();
  }, 60000);
  // Автологування дня що 5 хвилин (рядок 3423)
  setInterval(() => useStore.getState().logDayData(), 5 * 60 * 1000);

  setupNativeBridge(() => {
    // Відповідник рядків 4422–4426 handleNativeEvent: негайне збереження +
    // спроба оновити Android-сповіщення.
    queueSave();
    saveState();
    setTimeout(updateForegroundService, 300);
  });

  // Автозбереження щохвилини (рядок 4455)
  setInterval(() => {
    if (!useStore.getState().backupBanner) saveState();
  }, 60000);
  // Свіжий snapshot одразу після завантаження (рядки 4456–4462)
  setTimeout(() => {
    if (!useStore.getState().backupBanner) saveState();
  }, 1500);
}

bootstrap();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
