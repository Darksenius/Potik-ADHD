import { useStore } from '../state/store';
import { nowHM, fmtDate, hmToString } from '../utils/date';
import type { ForegroundNotifPayload, NotifZoneTimelineItem } from '../types';

/** colorDot() — рядки 3932-3947. Hex-колір → найближчий емодзі-кружечок. */
export function colorDot(hex: string | null | undefined): string {
  if (!hex) return '⚪';
  let h = hex.replace('#', '');
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  const r = parseInt(h.substr(0, 2), 16) / 255;
  const g = parseInt(h.substr(2, 2), 16) / 255;
  const b = parseInt(h.substr(4, 2), 16) / 255;
  const mx = Math.max(r, g, b);
  const mn = Math.min(r, g, b);
  const d = mx - mn;
  if (d < 0.08) return mx > 0.6 ? '⚪' : '⚫';
  let hue: number;
  if (mx === r) hue = ((g - b) / d + 6) % 6;
  else if (mx === g) hue = (b - r) / d + 2;
  else hue = (r - g) / d + 4;
  hue *= 60;
  if (hue < 15 || hue >= 330) return '🔴';
  if (hue < 45) return '🟠';
  if (hue < 70) return '🟡';
  if (hue < 170) return '🟢';
  if (hue < 255) return '🔵';
  if (hue < 320) return '🟣';
  return '🔴';
}

/**
 * fgNotifText() — ПОВНИЙ перенос, рядки 3948-4097. `r.ico` для рутини в
 * оригіналі теж завжди порожній (RecurItem його не задає — вочевидь
 * недобудована фіча), тому тут так само завжди ''.
 */
export function buildForegroundNotifPayload(): ForegroundNotifPayload {
  const s = useStore.getState();
  const hm = nowHM();
  const zones = s.getActiveZones(hm);
  const pz = zones[0];
  const zoneDots = zones.length > 1 && pz.id !== 0 ? zones.slice(0, 5).map((z) => colorDot(z.color)).join('') : '';
  const slots = pz.slots && pz.slots.length ? pz.slots[0].s + '–' + pz.slots[pz.slots.length - 1].e : '';

  const todayStr = fmtDate(new Date());
  const allActive = s.tasks.filter((t) => {
    if (t.someday || t.trashed) return false;
    if (t.planDate && t.planDate > todayStr && !t.done) return false;
    if (t.done && t.doneDate !== todayStr) return false;
    if (t.snoozeUntil && Date.now() < t.snoozeUntil && !t.done) return false;
    return true;
  });
  const undoneTasks = allActive.filter((t) => !t.done);
  const doneCnt = allActive.length - undoneTasks.length;
  const total = allActive.length;
  const prioIds = s.priorities.filter((x): x is number => !!x);
  const prioCount = prioIds.length;

  const notifPool = undoneTasks.filter((t) => {
    if (prioIds.indexOf(t.id) >= 0) return false;
    const fired = (t.type === 'alarm' && t.alarmFired) || (t.type === 'sched' && t.firedSched);
    if (fired) return true;
    if (t.zoneId) return t.zoneId === pz.id;
    return true;
  });
  const notifRank = (t: (typeof notifPool)[number]) => {
    const fired = (t.type === 'alarm' && t.alarmFired) || (t.type === 'sched' && t.firedSched);
    if (fired) return 0;
    if (pz.id && t.zoneId === pz.id) return 1;
    return 2;
  };
  notifPool.sort((a, b) => notifRank(a) - notifRank(b));
  const topN = notifPool.slice(0, 7);
  const taskLines = topN.map((t) => t.title).join('|');
  const taskList = topN.map((t) => ({ id: t.id, title: t.title, type: t.type || 'simple', counter: t.counter || 0, counterTarget: t.counterTarget || 10 }));

  const routineParts: string[] = [];
  s.recur.forEach((r) => {
    if (r.unit === 'check') routineParts.push('' + r.nm + (r.done ? ' ✓' : ' ○'));
    else routineParts.push('' + r.nm + ' ' + r.val + (r.unit === 'ml' ? 'мл' : r.unit === 'min' ? 'хв' : ''));
  });
  const routine = routineParts.join('  ·  ');
  const routineList = s.recur.map((r) => ({ id: r.id, nm: r.nm, ico: '', unit: r.unit || '', val: r.val || 0, step: r.step || 1, done: !!r.done }));

  const schedList: { id: number | string; title: string; dueMs: number; fired: boolean }[] = s.tasks
    .filter((t) => t.type === 'sched' && !t.done && !t.trashed && t.schedDate && t.schedTime)
    .map((t) => {
      let dueMs = 0;
      try {
        dueMs = new Date(t.schedDate + 'T' + t.schedTime + ':00').getTime();
      } catch {
        /* невалідна дата — dueMs лишається 0 */
      }
      return { id: t.id, title: t.title, dueMs, fired: !!t.firedSched };
    });

  const _now = Date.now();
  s.tasks
    .filter((t) => t.type === 'sched' && !t.done && !t.trashed && t.schedDate && t.schedTime && !t.firedPre)
    .forEach((t) => {
      let preMs = 0;
      try {
        const pd = new Date(t.schedDate + 'T12:00:00');
        pd.setDate(pd.getDate() - 1);
        preMs = pd.getTime();
      } catch {
        /* невалідна дата — пропускаємо передвісник */
      }
      if (preMs > _now) schedList.push({ id: t.id + '_pre', title: 'Завтра ' + t.schedTime + ': ' + t.title, dueMs: preMs, fired: false });
    });

  s.tasks
    .filter((t) => t.type === 'alarm' && t.alarmTime && !t.done && !t.trashed)
    .forEach((t) => {
      let dueMs = 0;
      try {
        const p = t.alarmTime!.split(':');
        const d = new Date();
        d.setHours(parseInt(p[0], 10), parseInt(p[1], 10), 0, 0);
        if (d.getTime() <= Date.now()) d.setDate(d.getDate() + 1);
        dueMs = d.getTime();
      } catch {
        /* невалідний час будильника — dueMs лишається 0 */
      }
      schedList.push({ id: t.id, title: t.title, dueMs, fired: !!t.alarmFired });
    });

  const zoneBaseline: NotifZoneTimelineItem[] = [];
  const dayOffT = s.planDayOff[todayStr] || [];
  s.zones.forEach((z) => {
    if (z.active === false) return;
    if (z.bound && !z.actPin) return;
    if (dayOffT.indexOf(z.id) >= 0) return;
    (z.slots || []).forEach((sl) => zoneBaseline.push({ id: z.id, s: sl.s, e: sl.e, nm: z.nm, desc: z.desc || '', color: z.color, prio: z.prio || 1 }));
  });
  const zoneTimeline = zoneBaseline.slice();
  s.dayBlocks(todayStr).forEach((b) => {
    const z = s.zones.find((zz) => zz.id === b.zoneId);
    if (!z) return;
    zoneTimeline.push({ id: z.id, s: b.s, e: b.e, nm: z.nm, desc: z.desc || '', color: z.color, prio: (z.prio || 1) + 10 });
  });

  const zoneTasksById: Record<number, string[]> = {};
  const zonelessTasks: string[] = [];
  const urgentTasks: string[] = [];
  undoneTasks.forEach((t) => {
    if (prioIds.indexOf(t.id) >= 0) return;
    const fired = (t.type === 'alarm' && t.alarmFired) || (t.type === 'sched' && t.firedSched);
    if (fired) {
      urgentTasks.push(t.title);
      return;
    }
    if (t.zoneId) (zoneTasksById[t.zoneId] = zoneTasksById[t.zoneId] || []).push(t.title);
    else zonelessTasks.push(t.title);
  });

  const focus = s.currentFocus && s.currentFocus !== pz.nm ? s.currentFocus : '';
  const desc = pz.desc || (focus ? '🧠 ' + focus : '');

  return {
    zone: (zoneDots ? zoneDots + ' ' : '') + pz.nm,
    zoneColor: pz.color || '#4f8ef7',
    zoneTimeline,
    zoneBaseline,
    zoneTasksById,
    zonelessTasks,
    urgentTasks,
    tlDate: todayStr,
    slots,
    desc,
    tasks: taskLines,
    taskList,
    routine,
    routineList,
    schedList,
    body: (prioCount > 0 ? '🎯 ' + prioCount + ' · ' : '') + (undoneTasks.length ? undoneTasks.length + ' активних · ' + doneCnt + ' виконано' : '✓ Всі виконано'),
    time: hmToString(hm),
    done: doneCnt,
    total,
    energy: s.energy || 0,
    title: 'Потік · ' + pz.nm + (focus ? ' · ' + focus : ''),
  };
}
