import { useStore } from '../state/store';
import { fmtDate } from '../utils/date';
import { nextTaskReminderAt, taskPreReminderAt } from '../utils/taskSchedule';

/** One scheduler for the visible app and the persisted native alarm snapshot. */
export function checkReminders(): void {
  const now = new Date();
  const date = fmtDate(now);
  const native = !!window.Capacitor?.isNativePlatform?.();
  const store = useStore.getState();
  store.checkDailyReset();
  if (store.zoneUsageDay !== date) store.recalculateZoneUsage();
  useStore.getState().resetRepeatingTasksFor(now);
  useStore.getState().checkZoneRepeats(now);
  const dueIds: number[] = [];
  let changed = false;
  const tasks = useStore.getState().tasks.map(task => {
    if (task.trashed || task.someday) return task;
    const due = nextTaskReminderAt(task, now);
    if (due === undefined) return task;
    const pre = taskPreReminderAt(task, due);
    const inMinute = (time: number | undefined) => time !== undefined && now.getTime() >= time && now.getTime() < time + 60000;
    const scheduled = task.type === 'sched' && !task.firedSched && inMinute(due);
    const alarm = task.type === 'alarm' && !task.alarmFired && inMinute(due);
    const advance = inMinute(pre);
    if (!scheduled && !alarm && !advance) return task;
    if (native) {
      // A visible highlight is not proof of Android notification delivery.
      // Only the native delivery command may set persisted fired flags.
      if (!useStore.getState().alarmTaskIds.includes(task.id)) { changed = true; dueIds.push(task.id); }
      return task;
    }
    changed = true;
    dueIds.push(task.id);
    // Native notifications are issued by Android; do not duplicate them from the WebView.
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      try { new Notification('Потік — Нагадування', { body: task.title, tag: 'flow-alarm-' + task.id }); } catch { /* Browser may not support notifications. */ }
    }
    return { ...task, ...(alarm ? { alarmFired: true } : {}), ...(scheduled ? { firedSched: true } : {}), ...(advance ? { firedPre: true } : {}) };
  });
  if (changed) useStore.setState(s => ({ tasks, alarmTaskIds: [...new Set([...s.alarmTaskIds, ...dueIds])] }));
}
