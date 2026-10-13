import { useStore } from '../state/store';
import { hasTaskCounter, taskCounterStep } from '../utils/taskCounter';
import { buildForegroundNotifPayload } from './notifPayload';
import { saveState, saveIsBlocked } from '../services/persistence';
import { fmtDate } from '../utils/date';
import { nextTaskReminderAt, taskPreReminderAt } from '../utils/taskSchedule';
import { buildQuestTimeline, isValidQuestDeferral } from '../utils/questPolicy';

/**
 * ═══════════════════════════════════════════════════════════════════════
 * ТОЧНИЙ контракт з нативним Android-кодом. Джерело істини — реальні Java-
 * класи, не здогад:
 *   - window.FlowBridge              ← android/.../FlowBridge.java (@JavascriptInterface)
 *   - window.Capacitor.Plugins.FlowNotif ← android/.../FlowPlugin.java (@CapacitorPlugin)
 *
 * Snapshot і підтвердження команд зберігаються однією Room-транзакцією.
 * Контракт змінюється разом з Java-плагіном та перевіряється тестами.
 * ═══════════════════════════════════════════════════════════════════════
 */
export interface FlowBridgeNative {
  save(json: string): void;
  saveWithEvents?(json: string, eventIds: string): boolean;
  getLastSaveError?(): string;
  archiveRecovery?(json: string): boolean;
  readRecoveryArchive?(): string;
  listRecoveryArchives?(): string;
  readRecoveryArchiveFile?(name: string): string;
  saveNotif(notifJson: string): void;
  load(): string;
  getEvents(): string;
  getEventEnvelopes?(): string;
  clearEvents(): void;
  appVersion(): string;
  writeBackup(json: string): void;
  readBackup(): string;
  hasBackup(): boolean;
  exportTxt(filename: string, content: string): boolean | void;
}

interface NativeListenerHandle {
  remove: () => Promise<void>;
}

export interface FlowNotifPlugin {
  // Capacitor's injected legacy Plugins API returns a handle synchronously.
  // Promise-based wrappers are also supported.
  addListener?: (event: 'flowEvent', listener: () => void) => NativeListenerHandle | Promise<NativeListenerHandle>;
  start(): Promise<void>;
  update(): Promise<void>;
  stop(): Promise<void>;
  drainEvents(): Promise<{ events: string }>;
  readEvents?: () => Promise<{ events: string }>;
  notificationPermission?: () => Promise<{ state: string }>;
  requestNotificationPermission?: () => Promise<{ state: string }>;
  openNotificationSettings?: () => Promise<void>;
}

declare global {
  interface Window {
    FlowBridge?: FlowBridgeNative;
    Capacitor?: {
      isNativePlatform: () => boolean;
      Plugins: {
        FlowNotif?: FlowNotifPlugin;
        [key: string]: unknown;
      };
    };
    /** Викликається нативним broadcast-сигналом "перевір чергу подій" (рядок 4136) */
    __flowDrainEvents?: () => void;
    /** MainActivity's existing notification navigation callback. */
    swTab?: (tab: string, element?: unknown) => void;
    /** Вшивається scripts/embed-oss.js у ЗІБРАНИЙ www/index.html (AGPL), рядок 4500 */
    __OSS__?: { github: string; license: string; source: string } | null;
  }
}

/** isCapacitor() — рядок 3927 */
export function isCapacitor(): boolean {
  return typeof window !== 'undefined' && !!window.Capacitor?.isNativePlatform?.();
}

/** Безпечний доступ до window.FlowBridge (може бути відсутній у звичайному браузері) */
export function flowBridge(): FlowBridgeNative | undefined {
  return typeof window !== 'undefined' ? window.FlowBridge : undefined;
}

function flowNotif(): FlowNotifPlugin | undefined {
  return typeof window !== 'undefined' ? window.Capacitor?.Plugins.FlowNotif : undefined;
}

function parseDeferredCommand(event: string, createdAt?: number) {
  const match = /^task_defer:(\d+):(.*)$/.exec(event);
  if (!match) return undefined;
  try {
    const deferral: unknown = JSON.parse(match[2]);
    if (!isValidQuestDeferral(deferral) || deferral.startAt < deferral.requestedAt
      || (Number.isFinite(createdAt) && Math.abs(deferral.requestedAt - createdAt!) > 60000)) return undefined;
    return { id: match[1], deferral };
  } catch { return undefined; }
}

/** Protect unfinished progress before bootstrap's day reset. Commands remain unacknowledged. */
export function preparePendingNativeDeferrals(): void {
  const fb = flowBridge();
  if (!fb?.getEventEnvelopes || saveIsBlocked()) return;
  try {
    const events = JSON.parse(fb.getEventEnvelopes());
    if (!Array.isArray(events)) return;
    const pending = new Map<string, import('../types').QuestDeferral>();
    for (const e of events) {
      if (!e || typeof e.event !== 'string' || !Number.isFinite(e.createdAt)) continue;
      const command = parseDeferredCommand(e.event, e.createdAt);
      const task = command && useStore.getState().tasks.find(t => String(t.id) === command.id);
      if (task && !task.done && !task.trashed && !task.someday
        && new Date(task.created).getTime() <= e.createdAt) pending.set(command!.id, command!.deferral);
    }
    if (pending.size) useStore.setState(s => ({ tasks: s.tasks.map(t => pending.has(String(t.id))
      ? { ...t, questDeferral: pending.get(String(t.id)), snoozeUntil: 0,
        firedSched: false, firedPre: false, alarmFired: false } : t) }));
  } catch { /* The ordinary durable drain retains unreadable commands for retry. */ }
}

/**
 * Applies native notification commands through the current store actions.
 * The notification capture commands carry an explicit task or note kind.
 */
export function handleNativeEvent(event: string | null | undefined, createdAt?: number): boolean {
  if (!event) return false;
  useStore.getState().checkDailyReset();
  useStore.getState().resetRepeatingTasksFor(new Date());
  const store = useStore.getState();
  let changed = false;
  // A fresh task must never inherit an old queued action merely by reusing an id.
  const belongsToTask = (task: (typeof store.tasks)[number]) => !Number.isFinite(createdAt)
    || !Number.isFinite(new Date(task.created).getTime()) || new Date(task.created).getTime() <= createdAt!;

  // Only explicit task IDs may complete tasks. The obsolete done_first event
  // cannot safely identify which row the user saw and is deliberately ignored.
  if (event.indexOf('task_done:') === 0) {
    const tid = event.slice(10);
    const pt = store.tasks.find((t) => String(t.id) === tid);
    if (pt && belongsToTask(pt) && !pt.done && !pt.trashed && pt.type !== 'negative') {
      store.setTaskDone(pt.id, true, createdAt);
      changed = true;
    }
  } else if (event.indexOf('task_skip:') === 0) {
    const sid = event.slice(10);
    const task = store.tasks.find(t => String(t.id) === sid && !t.trashed && !t.done);
    if (task && belongsToTask(task)) {
      store.deferTask(task.id, 'next-zone', createdAt);
      changed = true;
    }
  } else if (event.indexOf('task_defer:') === 0) {
    const command = parseDeferredCommand(event, createdAt);
    if (!command) return false;
    const task = store.tasks.find(t => String(t.id) === command.id && !t.trashed && !t.done);
    if (!task || !belongsToTask(task)) return false;
    const deferral = command.deferral;
    store.deferTask(task.id, deferral.kind, deferral.requestedAt, deferral);
    changed = true;
  } else if (event.indexOf('open_task:') === 0) {
    const task = store.tasks.find(t => String(t.id) === event.slice('open_task:'.length) && !t.trashed);
    if (!task || !belongsToTask(task)) return false;
    store.showPage('main');
    store.switchTab('alltasks');
    store.requestEditTask(task.id);
    // Navigation is not an edit, but the caller should refresh the interface.
    changed = true;
  } else if (/^task_(inc|dec):/.test(event)) {
    const task = store.tasks.find(t => String(t.id) === event.split(':')[1]);
    if (task && belongsToTask(task) && !task.trashed && !task.done && !task.someday && hasTaskCounter(task)) {
      store.changeCounter(task.id, taskCounterStep(task) * (event.startsWith('task_inc:') ? 1 : -1));
      changed = true;
    }
  } else if (event.indexOf('rc_inc:') === 0) {
    store.incRecur(event.slice(7));
    changed = true;
  } else if (event.indexOf('rc_dec:') === 0) {
    store.decRecur(event.slice(7));
    changed = true;
  } else if (event.indexOf('rc_done:') === 0) {
    store.toggleRecurCheck(event.slice(8));
    changed = true;
  } else if (event === 'daily_reset') {
    // checkDailyReset() тепер реалізовано в lifecycleSlice (раніше був TODO).
    useStore.getState().checkDailyReset();
    changed = true;
  } else if (event.indexOf('sched_fired:') === 0) {
    const [fid, deliveredAt] = event.slice(12).split(':');
    const occurrence = Number(deliveredAt);
    const baseId = fid.endsWith('_pre') ? fid.slice(0, -4) : fid;
    const task = store.tasks.find(t => String(t.id) === baseId);
    if (!task || !Number.isFinite(occurrence) || occurrence <= 0) return false;
    // Validate against the delivered day: a queued signal can be read much later.
    const timeline = task.questDeferral ? buildQuestTimeline({
      zones: store.zones, planDayOff: store.planDayOff,
      dayBlocks: store.dayBlocks, now: new Date(task.questDeferral.requestedAt),
    }) : undefined;
    const due = nextTaskReminderAt(task, new Date(occurrence), timeline);
    if (due === undefined || (fid.endsWith('_pre') ? taskPreReminderAt(task, due) : due) !== occurrence) return false;
    if (fid.slice(-4) === '_pre') {
      const pid = fid.slice(0, -4);
      const pt = store.tasks.find((t) => String(t.id) === pid);
      if (pt) {
        store.markPreReminderFired(pt.id);
        changed = true;
      }
    } else {
      const ft = store.tasks.find((t) => String(t.id) === fid);
      if (ft) {
        store.markScheduledFired(ft.id);
        changed = true;
      }
    }
  } else if (event.indexOf('task_remind:') === 0) {
    // Оригінал лише перемальовував список (renderTasks()) — у React це не
    // потребує явної дії, компоненти й так реагують на зміну relevant стану.
    changed = true;
  } else if (event.indexOf('capture_task_v2:') === 0) {
    const match = /^capture_task_v2:(\d+):(.*)$/.exec(event);
    if (!match) return false;
    const id = Number(match[1]);
    if (!Number.isSafeInteger(id) || id <= 0 || id >= Number.MAX_SAFE_INTEGER) return false;
    let payload: { title?: unknown; createdAt?: unknown };
    try { payload = JSON.parse(match[2]); } catch { return false; }
    if (!payload || typeof payload.title !== 'string' || !payload.title.trim()
      || typeof payload.createdAt !== 'number' || !Number.isFinite(payload.createdAt)
      || (Number.isFinite(createdAt) && Math.abs(payload.createdAt - createdAt!) > 60000)) return false;
    store.addQuickTaskFromShade(payload.title.trim(), id, payload.createdAt);
    changed = true;
  } else if (event.indexOf('capture_task:') === 0) {
    const title = event.slice('capture_task:'.length).trim();
    if (!title) return false;
    store.addQuickTaskFromShade(title);
    changed = true;
  } else if (event.indexOf('capture_note:') === 0) {
    const txt = event.slice('capture_note:'.length).trim();
    if (!txt) return false;
    store.addQuickNote(txt, 'impulse');
    changed = true;
  } else if (event.indexOf('note:') === 0) {
    const txt = event.slice(5).trim();
    if (!txt) return false;
    store.addQuickNote(txt, 'impulse');
    changed = true;
  }

  return changed;
}

/**
 * window.__flowDrainEvents — рядки 4136–4145. Нативний broadcast сигналізує
 * «перевір чергу», ми забираємо події через Capacitor-плагін і проганяємо
 * кожну через handleNativeEvent.
 */
let draining = false;
export function drainNativeEvents(onChanged: () => void): void {
  const fp = flowNotif();
  const fb = flowBridge();
  if (!fp || draining || saveIsBlocked() || useStore.getState().backupBanner) return;
  if (fp.readEvents && fb?.saveWithEvents) {
    draining = true;
    fp.readEvents().then(res => {
      const events: { id: number; event: string; createdAt: number }[] = JSON.parse(res.events);
      if (!Array.isArray(events) || !events.length || events.some(e => !e || !Number.isSafeInteger(e.id) || e.id <= 0 || typeof e.event !== 'string' || !Number.isFinite(e.createdAt))) return;
      preparePendingNativeDeferrals();
      const before = useStore.getState();
      try {
        for (const e of events) {
          const task = useStore.getState().tasks.find(t => String(t.id) === e.event.split(':')[1]);
          const staleDay = fmtDate(new Date(e.createdAt)) !== fmtDate(new Date());
          const resetsByDay = !task || !!task.repeat && !['none', 'interval'].includes(task.repeat) || task.type === 'timewin';
          const carriedOccurrence = task?.questDeferral && e.createdAt >= task.questDeferral.requestedAt;
          if (staleDay && (/^rc_/.test(e.event) || resetsByDay && !carriedOccurrence && /^(task_done:|task_inc:|task_dec:)/.test(e.event))) {
            // A stale toggle must never silently change today's reset routine.
            useStore.getState().addQuickNote('Дія зі сповіщення ' + new Date(e.createdAt).toLocaleString('uk') + ': ' + e.event + (task ? ' · ' + task.title : '') + '. Перевір і застосуй вручну: день уже змінився.', 'impulse');
          } else handleNativeEvent(e.event, e.createdAt);
        }
        useStore.getState().resetRepeatingTasksFor(new Date());
        if (!saveState(events.map(e => e.id))) {
          useStore.setState(before);
          useStore.getState().showToast('Дію зі сповіщення ще не збережено. Вона лишилася в черзі для повторної спроби.');
          return;
        }
      } catch {
        useStore.setState(before);
        useStore.getState().showToast('Не вдалося застосувати дію зі сповіщення. Команду збережено в черзі.');
        return;
      }
      onChanged();
    }).catch(() => {}).then(() => { draining = false; });
    return;
  }
  fp.drainEvents()
    .then((res) => {
      if (!res || !res.events || res.events === '[]') return;
      let events: string[];
      try {
        events = JSON.parse(res.events);
      } catch {
        return;
      }
      if (!Array.isArray(events)) return;
      let anyChanged = false;
      events.forEach((e) => {
        if (handleNativeEvent(e)) anyChanged = true;
      });
      if (anyChanged) onChanged();
    })
    .catch(() => {
      /* тихо ігноруємо, як в оригіналі */
    });
}

let fgStarted = false;

/** startForegroundService() — рядки 3925, 4099-4113 */
export function startForegroundService(): void {
  if (!isCapacitor()) return;
  const fp = flowNotif();
  if (!fp || typeof fp.start !== 'function') {
    console.log('FLOW: FlowNotif plugin not ready yet');
    return;
  }
  const snap = buildForegroundNotifPayload();
  flowBridge()?.saveNotif(JSON.stringify(snap));
  fp.start()
    .then(() => {
      fgStarted = true;
      console.log('FLOW notification started');
    })
    .catch((e) => console.log('FLOW notif error:', e));
}

/** updateForegroundService() — рядки 4115-4123 */
export function updateForegroundService(): void {
  if (!isCapacitor() || !fgStarted) return;
  const fp = flowNotif();
  if (!fp || typeof fp.update !== 'function') return;
  const snap = buildForegroundNotifPayload();
  flowBridge()?.saveNotif(JSON.stringify(snap));
  fp.update().catch(() => {});
}

/** stopForegroundService() — рядки 4125-4131 */
export function stopForegroundService(): void {
  if (!isCapacitor() || !fgStarted) return;
  try {
    flowNotif()?.stop().catch(() => {});
  } catch {
    /* плагін відсутній — тихо ігноруємо, як в оригіналі */
  }
  fgStarted = false;
}

export async function getNativeNotificationPermission(): Promise<string> {
  try { return (await flowNotif()?.notificationPermission?.())?.state || 'unavailable'; } catch { return 'unavailable'; }
}
export async function requestNativeNotificationPermission(): Promise<string> {
  try { return (await flowNotif()?.requestNotificationPermission?.())?.state || 'unavailable'; } catch { return 'unavailable'; }
}
export async function openNativeNotificationSettings(): Promise<void> {
  try { await flowNotif()?.openNotificationSettings?.(); } catch { useStore.getState().showToast('Відкрий дозволи Потоку в налаштуваннях Android.'); }
}

/**
 * Підключає нативний міст: слухач 'deviceready' + expose window.__flowDrainEvents.
 * Викликати ОДИН РАЗ із src/main.tsx при старті застосунку.
 *
 * onChanged зберігає зміни після обробки події та оновлює сповіщення.
 */
export function setupNativeBridge(onChanged: () => void): void {
  if (typeof window === 'undefined') return;

  window.__flowDrainEvents = () => drainNativeEvents(onChanged);
  window.swTab = (tab) => {
    if (tab !== 'tasks') return;
    useStore.getState().showPage('main');
    useStore.getState().switchTab('tasks');
  };

  let connected = false;
  const connect = () => {
    const fp = flowNotif();
    if (!fp || connected) return;
    connected = true;
    setTimeout(() => startForegroundService(), 800);
    try {
      Promise.resolve(fp.addListener?.('flowEvent', () => drainNativeEvents(onChanged)))
        .catch(e => console.log('FLOW event listener:', e));
    } catch (e) {
      // A listener failure must not prevent React from mounting. MainActivity's
      // polling and the initial drain below still process queued native events.
      console.log('FLOW event listener:', e);
    }
    setTimeout(() => drainNativeEvents(onChanged), 1000);
  };
  document.addEventListener('deviceready', connect);
  // Capacitor may already be ready when the module script is evaluated.
  if (isCapacitor()) connect();
}
