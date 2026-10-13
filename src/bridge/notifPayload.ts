import { isCalendarRepeat, isTaskAvailable, nextTaskReminderAt, questDeferralReminderAt, repeatDueOn, taskPlanDate, taskPreReminderAt } from '../utils/taskSchedule';
import { useStore } from '../state/store';
import { hasTaskCounter, taskCounterStep, taskCounterTarget } from '../utils/taskCounter';
import { nowHM, fmtDate, hmToString, localDateTimeMs, normalizeTime } from '../utils/date';
import type { ForegroundNotifPayload, NotifSchedListItem, NotifZoneTimelineItem, Task } from '../types';
import { buildQuestTimeline, getCurrentQuestSegment, selectQuestTasks } from '../utils/questPolicy';

/** Native alarms retain their calendar rules when the WebView is absent. */
export function recurringReminderRows(task: Task, now = new Date(), timeline?: readonly import('../types').QuestTimelineSegment[]): NotifSchedListItem[] {
  if (!isCalendarRepeat(task) || !['sched', 'alarm'].includes(task.type) || task.trashed || task.someday || task.reminderEnabled === false) return [];
  if (task.questDeferral) {
    const fired = task.type === 'sched' ? task.firedSched : task.alarmFired;
    if (fired) return [];
    const dueMs = questDeferralReminderAt(task, timeline);
    return dueMs === undefined ? [] : [{ id: task.id, title: task.title, dueMs, fired: false }];
  }
  const localTime = normalizeTime(task.type === 'sched' ? task.schedTime : task.alarmTime);
  if (!localTime) return [];
  const today = fmtDate(now);
  const first = taskPlanDate(task) || fmtDate(new Date(task.created));
  const rows: NotifSchedListItem[] = [];
  const lead = task.type === 'sched' ? Math.max(0, task.remindBeforeMinutes || 0) : 0;
  for (const pre of lead > 0 ? [false, true] : [false]) {
    for (let offset = 0; offset < 370; offset++) {
      const date = new Date(now); date.setDate(date.getDate() + offset);
      const day = fmtDate(date);
      if (day < first || !repeatDueOn(task, date) || (task.done && day <= (task.doneDate || today))) continue;
      const mainDue = localDateTimeMs(day, localTime)!;
      if (task.snoozeUntil && mainDue < task.snoozeUntil) continue;
      if (day === today && (pre ? task.firedPre : task.type === 'sched' ? task.firedSched : task.alarmFired)) continue;
      if (pre && mainDue <= now.getTime()) continue;
      rows.push({ id: pre ? task.id + '_pre' : task.id, title: pre ? 'Незабаром: ' + task.title : task.title,
        dueMs: mainDue - (pre ? lead * 60000 : 0), fired: false, repeat: task.repeat,
        repeatDays: task.repeatDays.slice(), anchorDate: day, localTime, advanceMinutes: pre ? lead : 0 });
      break;
    }
  }
  return rows;
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
  const now = new Date();
  const questTimeline = buildQuestTimeline({
    zones: s.zones,
    planDayOff: s.planDayOff,
    dayBlocks: day => s.dayBlocks(day),
    now,
  });
  const currentSegment = getCurrentQuestSegment(questTimeline, now);
  const currentZone = s.zones.find(zone => zone.id === currentSegment?.zoneId);
  const pz = currentZone
    ? { ...currentZone, id: currentZone.id, nm: currentZone.nm, color: currentZone.color, desc: currentZone.desc || '', prio: currentZone.prio || 1, slots: currentZone.slots }
    : zones[0];
  const slots = pz.slots && pz.slots.length ? pz.slots[0].s + '–' + pz.slots[pz.slots.length - 1].e : '';

  const todayStr = fmtDate(new Date());
  const questContext = { now, timeline: questTimeline, zones: s.zones, priorities: s.priorities };
  const topN = selectQuestTasks(s.tasks, questContext, 5);
  const undoneTasks = selectQuestTasks(s.tasks, questContext, s.tasks.length);
  const doneCnt = s.tasks.filter(t => t.done && t.doneDate === todayStr && isTaskAvailable(t)
    && (t.zoneId == null || t.zoneId === currentSegment?.zoneId)).length;
  const total = undoneTasks.length + doneCnt;
  const questTasks = s.tasks.map(task => ({
    ...task,
    priorityIndex: s.priorities.indexOf(task.id),
  }));
  const prioCount = topN.filter(task => task.priorityIndex >= 0).length;
  const taskLines = topN.map((t) => t.title).join('|');
  // Journal entries record an event, rather than completing a task. Do not
  // offer the native "Done" action for them.
  const taskList = topN.filter(t => t.type !== 'negative').map((t) => ({ id: t.id, title: t.title, type: t.type || 'simple', counter: t.counter || 0, counterTarget: taskCounterTarget(t), counterEnabled: hasTaskCounter(t), counterStep: taskCounterStep(t), counterUnit: t.counterUnit || 'count', repeat: t.repeat, repeatDays: t.repeatDays.slice(), anchorDate: taskPlanDate(t) || fmtDate(new Date(t.created)), counterDay: t.counterDay || t.completionCreditDate || fmtDate(new Date(t.created)) }));

  const routineParts: string[] = [];
  s.recur.forEach((r) => {
    if (r.unit === 'check') routineParts.push('' + r.nm + (r.done ? ' ✓' : ' ○'));
    else routineParts.push('' + r.nm + ' ' + r.val + (r.unit === 'ml' ? 'мл' : r.unit === 'min' ? 'хв' : ''));
  });
  const routine = routineParts.join('  ·  ');
  const routineList = s.recur.map((r) => ({ id: r.id, nm: r.nm, ico: '', unit: r.unit || '', val: r.val || 0, step: r.step || 1, done: !!r.done }));

  const schedList: NotifSchedListItem[] = [];
  for (const task of s.tasks) {
    if (isCalendarRepeat(task)) { schedList.push(...recurringReminderRows(task, now, questTimeline)); continue; }
    const due = nextTaskReminderAt(task, now, questTimeline);
    if (due === undefined) continue;
    schedList.push({ id: task.id, title: task.title, dueMs: due, fired: task.type === 'sched' ? !!task.firedSched : !!task.alarmFired && fmtDate(new Date(due)) === todayStr });
    const pre = taskPreReminderAt(task, due);
    // Keep an unacknowledged advance reminder until the main deadline, including
    // a delayed Android receiver; an autosave must not silently withdraw it.
    if (pre !== undefined && due > Date.now()) schedList.push({ id: task.id + '_pre', title: 'Незабаром: ' + task.title, dueMs: pre, fired: false });
  }

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
  topN.forEach((t) => {
    if (t.priorityIndex >= 0) urgentTasks.push(t.title);
    if (t.zoneId) (zoneTasksById[t.zoneId] = zoneTasksById[t.zoneId] || []).push(t.title);
    else zonelessTasks.push(t.title);
  });

  const focus = s.currentFocus && s.currentFocus !== pz.nm ? s.currentFocus : '';
  const desc = pz.desc || focus;

  return {
    zone: pz.nm,
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
    questTasks,
    questTimeline,
    questTimelineUntil: questTimeline[questTimeline.length - 1]?.endAt || now.getTime(),
    questZones: s.zones.map(z => ({ id: z.id, nm: z.nm, desc: z.desc || '', color: z.color || '#4f8ef7' })),
    routine,
    routineList,
    schedList,
    body: (prioCount > 0 ? 'Пріоритети: ' + prioCount + ' · ' : '') + (undoneTasks.length ? undoneTasks.length + ' активних · ' + doneCnt + ' виконано' : 'Зараз немає доречних задач'),
    time: hmToString(hm),
    done: doneCnt,
    total,
    energy: s.energy || 0,
    title: 'Потік · ' + pz.nm + (focus ? ' · ' + focus : ''),
  };
}
