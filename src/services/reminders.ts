import { useStore } from '../state/store';
import { fmtDate, hmToString } from '../utils/date';

/** The legacy updateClock called checkAlarms; rendering a clock does not run
 * those domain transitions. Keep the scheduler independent of the active tab. */
export function checkReminders(): void {
  const now = new Date();
  const date = fmtDate(now);
  const time = hmToString({ h: now.getHours(), m: now.getMinutes() });
  const store = useStore.getState();
  store.checkDailyReset();
  if (store.zoneUsageDay !== date) store.recalculateZoneUsage();
  const due: number[] = [];
  let changed = false;
  const tasks = useStore.getState().tasks.map(task => {
    if (task.trashed || task.someday) return task;
    if (task.done && task.repeat === 'interval' && task.repeatMs && task.nextRepeatAt && now.getTime() >= task.nextRepeatAt) {
      changed = true;
      return { ...task, done: false, nextRepeatAt: 0 };
    }
    if (task.done) return task;
    const alarm = task.type === 'alarm' && task.alarmTime === time && !task.alarmFired;
    const scheduled = task.type === 'sched' && task.schedDate === date && task.schedTime === time && !task.firedSched;
    if (!alarm && !scheduled) return task;
    changed = true;
    due.push(task.id);
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      try { new Notification('⏰ Потік — Будильник', { body: task.title, tag: 'flow-alarm-' + task.id }); } catch { /* WebView may not support this API. */ }
    }
    return { ...task, alarmFired: true, ...(scheduled ? { firedSched: true } : {}) };
  });
  if (changed) useStore.setState(s => ({ tasks, alarmTaskIds: [...new Set([...s.alarmTaskIds, ...due])] }));
}
