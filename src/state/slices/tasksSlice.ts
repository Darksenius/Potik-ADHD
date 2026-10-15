import type { AppSlice } from '../store';
import type { Task, TaskType, TaskHistoryEvent, ChecklistItem, QuestDeferral, QuestDeferralKind } from '../../types';
import taskTypeCatalog from '../../content/task-types.json' with { type: 'json' };
import { createTaskRecord } from '../../utils/taskFactory';
import { historyEvent, nextHistorySequence, seedKnownCompletions, taskOccurrenceId } from '../../utils/taskHistory';
import { fmtDate, nowHM, normalizeTime, hmToString } from '../../utils/date';
import { hasTaskCounter, resetTaskProgress, taskCounterTarget } from '../../utils/taskCounter';
import { buildQuestTimeline, createQuestDeferral, isValidQuestDeferral } from '../../utils/questPolicy';

/** TXP — рядок 1239: базова нагорода XP за тип задачі при виконанні. */
const TASK_XP = Object.keys(taskTypeCatalog).reduce((xp, type) => {
  xp[type as TaskType] = taskTypeCatalog[type as TaskType].xp;
  return xp;
}, {} as Record<TaskType, number>);

const REPEAT_UNIT_MS: Record<string, number> = { sec: 1000, min: 60000, hour: 3600000, day: 86400000, week: 604800000, month: 2592000000 };
const TRASH_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export interface TasksSlice {
  tasks: Task[];
  taskHistory: TaskHistoryEvent[];
  /** Наступний вільний id (було S.nid) */
  nid: number;

  createTask: (title: string, type: TaskType, zoneId?: number | null, alarmTime?: string) => Task;
  toggleTask: (id: number) => void;
  setTaskDone: (id: number, done: boolean, completedAt?: number) => void;
  recordPastTaskDone: (id: number, completedAt: number) => boolean;
  skipTask: (id: number) => void;
  toSomeday: (id: number) => void;
  fromSomeday: (id: number) => void;
  addQuickTaskFromShade: (title: string, requestedId?: number, createdAt?: number) => void;
  markScheduledFired: (id: number) => void;
  markPreReminderFired: (id: number) => void;

  moveTask: (id: number, dir: -1 | 1, visibleIds: number[]) => void;
  deleteTask: (id: number) => void;
  restoreTask: (id: number) => void;
  hardDeleteTask: (id: number) => void;
  purgeOldTrash: () => void;
  duplicateTask: (id: number) => void;
  toggleExpanded: (id: number) => void;

  toggleChecklistItem: (taskId: number, index: number) => void;
  addChecklistItem: (taskId: number, text: string) => void;
  editChecklistItem: (taskId: number, index: number, text: string) => void;
  deleteChecklistItem: (taskId: number, index: number) => void;
  changeCounter: (taskId: number, delta: number) => void;
  updateCounterTarget: (taskId: number, target: number) => void;
  toggleCtxTag: (taskId: number, tag: string) => void;
  saveTask: (id: number | null, patch: Partial<Task> & { title: string; type: TaskType }) => number;
  /** efSnooze(x) — рядки 2402–2411. x='tomorrow' або хвилини. */
  snoozeTask: (id: number, x: 'tomorrow' | number, requestedAt?: number) => void;
  deferTask: (id: number, kind: QuestDeferralKind, requestedAt?: number, explicitDeferral?: QuestDeferral) => void;
  /** updNote(tid,v) — рядок 2250 */
  updateNote: (id: number, text: string) => void;
  /** updAlarm(tid,v) — рядок 2251 */
  updateAlarm: (id: number, time: string) => void;
  /** rmTag(tid,i) — рядок 2259 */
  removeTag: (id: number, index: number) => void;
}

export const createTasksSlice: AppSlice<TasksSlice> = (set, get) => ({
  tasks: [],
  taskHistory: [],
  nid: 300,

  createTask: (title, type, zoneId, alarmTime) => {
    const nid = get().nid;
    const zone = zoneId ? get().zones.find((z) => z.id === zoneId) : null;
    const task = createTaskRecord(nid, title, type, zoneId ?? null, zone, alarmTime);
    set((s) => ({ tasks: [task, ...s.tasks], nid: s.nid + 1 }));
    return task;
  },

  toggleTask: (id) => {
    const t = get().tasks.find((x) => x.id === id);
    if (!t || t.trashed) return;

    if (t.type === 'negative') {
      set((s) => ({
        tasks: s.tasks.map((x) => (x.id === id ? { ...x, counter: (x.counter || 0) + 1, counterDay: fmtDate(new Date()) } : x)),
      }));
      get().incrementNegCount();
      get().award(-(t.negXp || 5));
      return;
    }

    get().setTaskDone(id, !t.done);
  },

  setTaskDone: (id, done, completedAt) => {
    const t = get().tasks.find((x) => x.id === id);
    if (!t || t.trashed || t.type === 'negative' || t.done === done) return;
    const shouldCredit = done && !t.completionCredited;
    const now = new Date(Number.isFinite(completedAt) ? completedAt! : Date.now());
    const completionCredited = done ? true : (t.completionCredited ?? t.done);
    const completionCreditDate = done
      ? (shouldCredit ? fmtDate(now) : t.completionCreditDate)
      : (completionCredited ? (t.completionCreditDate || t.doneDate || fmtDate(now)) : t.completionCreditDate);
    const originAt = t.questDeferral?.requestedAt ?? now.getTime();
    const occurrenceId = taskOccurrenceId(t, now.getTime(), t.zoneId
      ? get().getZoneOccurrenceKey(t.zoneId, new Date(originAt)) : undefined);
    set((s) => ({
      taskHistory: [...seedKnownCompletions(s.taskHistory, s.tasks),
        historyEvent(t, done ? 'completed' : 'reopened', now.getTime(), occurrenceId,
          nextHistorySequence(seedKnownCompletions(s.taskHistory, s.tasks)))],
      tasks: s.tasks.map((x) => {
        if (x.id !== id) return x;
        const updated: Task = { ...x, done, completionCredited, completionCreditDate, occurrenceId };
        if (x.type === 'timewin') updated.completedToday = done;
        if (x.type === 'zonelinked') updated.zoneDoneToday = done;
        if (done) {
          updated.doneDate = fmtDate(now);
          updated.doneAt = now.toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' });
          updated.snoozeUntil = 0;
          updated.completionDeferral = x.questDeferral;
          updated.questDeferral = undefined;
          updated.nextRepeatAt = x.repeat === 'interval' && x.repeatMs ? now.getTime() + x.repeatMs : 0;
        } else {
          updated.doneDate = undefined;
          updated.doneAt = undefined;
          updated.questDeferral = x.completionDeferral;
          updated.completionDeferral = undefined;
          updated.nextRepeatAt = 0;
        }
        return updated;
      }),
      priorities: done ? s.priorities.map((pid) => (pid === id ? null : pid)) : s.priorities,
      alarmTaskIds: done ? s.alarmTaskIds.filter(taskId => taskId !== id) : s.alarmTaskIds,
    }));

    if (shouldCredit) {
      const day = fmtDate(now);
      if (day < fmtDate(new Date())) set(s => ({ planDayLog: { ...s.planDayLog,
        [day]: { ...(s.planDayLog[day] || { focus: [], energy: 0, routineDone: {}, priorities: [], tasksDone: 0 }),
          tasksDone: get().dayStats(day).tasksDone } } }));
      get().incrementDoneCount();
      get().award(TASK_XP[t.type] ?? 10);
    }
  },

  /** A durable tap from yesterday records that occurrence, never today's progress. */
  recordPastTaskDone: (id, completedAt) => {
    const task = get().tasks.find(candidate => candidate.id === id);
    if (!task || task.type === 'negative' || !Number.isFinite(completedAt)
      || new Date(task.created).getTime() > completedAt) return false;
    const date = new Date(completedAt);
    const day = fmtDate(date);
    const key = task.zoneId ? get().getZoneOccurrenceKey(task.zoneId, date) : undefined;
    const snapshot: Task = { ...task, occurrenceId: undefined, counterDay: day, planDate: day,
      questDeferral: undefined, zoneOccurrenceKey: key,
      counter: undefined, items: undefined };
    const occurrenceId = taskOccurrenceId(snapshot, completedAt, key);
    const history = seedKnownCompletions(get().taskHistory, get().tasks);
    const previous = history.filter(event => event.occurrenceId === occurrenceId);
    if (previous.some(event => event.kind === 'completed')
      || previous.some(event => event.at >= completedAt)) return false;
    set({ taskHistory: [...history, historyEvent(snapshot, 'completed', completedAt,
      occurrenceId, nextHistorySequence(history))] });
    get().incrementDoneCount();
    get().award(TASK_XP[task.type]);
    return true;
  },

  skipTask: (id) => {
    set((s) => {
      const idx = s.tasks.findIndex((x) => x.id === id);
      if (idx < 0) return s;
      const next = s.tasks.slice();
      const [moved] = next.splice(idx, 1);
      next.push(moved);
      return { tasks: next };
    });
  },

  toSomeday: (id) => {
    set((s) => ({
      tasks: s.tasks.map((x) => (x.id === id ? {
        ...x, someday: true, done: false,
        questDeferral: undefined,
        completionDeferral: undefined,
        completionCredited: x.completionCredited ?? x.done,
        completionCreditDate: (x.completionCredited ?? x.done) ? (x.completionCreditDate || x.doneDate || fmtDate(new Date())) : x.completionCreditDate,
        doneDate: undefined, doneAt: undefined, nextRepeatAt: 0,
        completedToday: x.type === 'timewin' ? false : x.completedToday,
        zoneDoneToday: x.type === 'zonelinked' ? false : x.zoneDoneToday,
      } : x)),
      priorities: s.priorities.map((pid) => (pid === id ? null : pid)),
      xpPopup: { text: '→ Колись', tone: 'muted' },
    }));
    get().showToast('Задачу перенесено в «Колись»');
  },

  fromSomeday: (id) => {
    set((s) => ({ tasks: s.tasks.map((x) => (x.id === id ? { ...x, someday: false } : x)) }));
  },

  addQuickTaskFromShade: (title, requestedId, createdAt) => {
    if (Number.isSafeInteger(requestedId) && requestedId! > 0) {
      // Room's durable capture id also identifies actions taken before the WebView opens.
      if (get().tasks.some(task => task.id === requestedId)) return;
      const created = get().createTask(title, 'simple', null, '');
      set(state => ({
        tasks: state.tasks.map(task => task.id === created.id ? {
          ...task, id: requestedId!, created: Number.isFinite(createdAt) ? new Date(createdAt!).toISOString() : task.created,
        } : task),
        nid: Math.max(state.nid, requestedId! + 1),
      }));
      return;
    }
    get().createTask(title, 'simple', null, '');
  },

  markScheduledFired: (id) => {
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, firedSched: true, alarmFired: true } : t)),
    }));
  },

  markPreReminderFired: (id) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, firedPre: true } : t)) }));
  },

  moveTask: (id, dir, visibleIds) => {
    set((s) => {
      const activeIdx: number[] = [];
      s.tasks.forEach((tk, gi) => {
        if (!tk.trashed && visibleIds.includes(tk.id)) activeIdx.push(gi);
      });
      let pos = -1;
      for (let k = 0; k < activeIdx.length; k++) {
        if (s.tasks[activeIdx[k]].id === id) {
          pos = k;
          break;
        }
      }
      if (pos < 0) return s;
      const target = pos + dir;
      if (target < 0 || target >= activeIdx.length) return s;
      const gi1 = activeIdx[pos];
      const gi2 = activeIdx[target];
      const tasks = s.tasks.slice();
      const tmp = tasks[gi1];
      tasks[gi1] = tasks[gi2];
      tasks[gi2] = tmp;
      return { tasks };
    });
  },

  deleteTask: (id) => {
    set((s) => ({
      taskHistory: seedKnownCompletions(s.taskHistory, s.tasks),
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, trashed: true, trashedAt: Date.now() } : t)),
      priorities: s.priorities.map((pid) => (pid === id ? null : pid)),
      alarmTaskIds: s.alarmTaskIds.filter((taskId) => taskId !== id),
    }));
  },

  restoreTask: (id) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, trashed: false, trashedAt: null } : t)) }));
  },

  hardDeleteTask: (id) => {
    set((s) => ({
      taskHistory: seedKnownCompletions(s.taskHistory, s.tasks),
      tasks: s.tasks.filter((t) => t.id !== id),
      priorities: s.priorities.map((pid) => (pid === id ? null : pid)),
      alarmTaskIds: s.alarmTaskIds.filter((taskId) => taskId !== id),
    }));
  },

  purgeOldTrash: () => {
    set((s) => {
      const tasks = s.tasks.filter((t) => !t.trashed || Date.now() - (t.trashedAt || 0) < TRASH_RETENTION_MS);
      const retainedIds = new Set(tasks.map((t) => t.id));
      return {
        taskHistory: seedKnownCompletions(s.taskHistory, s.tasks),
        tasks,
        priorities: s.priorities.map((pid) => pid !== null && !retainedIds.has(pid) ? null : pid),
        alarmTaskIds: s.alarmTaskIds.filter((taskId) => retainedIds.has(taskId)),
      };
    });
  },

  duplicateTask: (id) => {
    const t = get().tasks.find((x) => x.id === id);
    if (!t) return;
    const nid = get().nid;
    const copy: Task = {
      ...JSON.parse(JSON.stringify(t)), id: nid, done: false, completionCredited: false,
      completionCreditDate: undefined, doneDate: undefined, doneAt: undefined, nextRepeatAt: 0, snoozeUntil: 0,
      occurrenceId: undefined,
      questDeferral: undefined, completionDeferral: undefined,
      completedToday: t.type === 'timewin' ? false : t.completedToday,
      zoneDoneToday: t.type === 'zonelinked' ? false : t.zoneDoneToday,
      expanded: false, title: t.title + ' (копія)',
      ...resetTaskProgress(t), alarmFired: false, firedSched: false, firedPre: false,
      created: new Date().toISOString(), zoneOccurrenceKey: undefined,
    };
    set((s) => {
      const idx = s.tasks.findIndex((x) => x.id === id);
      const tasks = s.tasks.slice();
      tasks.splice(idx, 0, copy);
      return { tasks, nid: s.nid + 1 };
    });
  },

  toggleExpanded: (id) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, expanded: !t.expanded } : t)) }));
  },

  toggleChecklistItem: (taskId, index) => {
    const t = get().tasks.find((x) => x.id === taskId);
    if (!t || t.trashed || t.done || t.type !== 'check' || !t.items?.[index]) return;
    const willBeDone = !t.items[index].done;
    const shouldCredit = willBeDone && !t.items[index].credited;
    set((s) => ({
      tasks: s.tasks.map((x) =>
        x.id === taskId ? { ...x, counterDay: fmtDate(new Date()), items: (x.items || []).map((it, i) => (i === index ? { ...it, done: willBeDone, credited: shouldCredit || it.credited } : it)) } : x
      ),
    }));
    if (shouldCredit) get().award(3);
  },

  addChecklistItem: (taskId, text) => {
    const trimmed = text.trim();
    const task = get().tasks.find(t => t.id === taskId);
    if (!trimmed || !task || task.trashed || task.type !== 'check') return;
    const item: ChecklistItem = { text: trimmed, done: false };
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === taskId ? { ...t, items: [...(t.items || []), item] } : t)),
    }));
  },

  editChecklistItem: (taskId, index, text) => {
    if (!text.trim()) return;
    set(s => ({ tasks: s.tasks.map(t => t.id === taskId && !t.trashed && t.type === 'check'
      ? { ...t, items: t.items?.map((it, i) => i === index ? { ...it, text: text.trim() } : it) } : t) }));
  },
  deleteChecklistItem: (taskId, index) => {
    set(s => ({ tasks: s.tasks.map(t => t.id === taskId && !t.trashed && t.type === 'check'
      ? { ...t, items: t.items?.filter((_, i) => i !== index) } : t) }));
  },

  changeCounter: (taskId, delta) => {
    const t = get().tasks.find((x) => x.id === taskId);
    if (!t || t.trashed || t.done || !hasTaskCounter(t) || !Number.isFinite(delta) || !delta) return;
    const next = Math.max(0, (t.counter || 0) + delta);
    if (!Number.isFinite(next)) return;
    const target = taskCounterTarget(t);
    const justHit = target > 0 && next >= target && !t.cntHit;
    const credited = Math.max(t.counterCreditedProgress ?? (t.counter || 0), next);
    const shouldCredit = next > (t.counterCreditedProgress ?? (t.counter || 0));
    set((s) => ({
      tasks: s.tasks.map((x) => (x.id === taskId ? { ...x, counter: next, counterDay: fmtDate(new Date()), counterCreditedProgress: credited, cntHit: justHit ? true : x.cntHit } : x)),
    }));
    if (shouldCredit) get().award(2);
    if (justHit) get().award(15);
  },

  updateCounterTarget: (taskId, target) => {
    if (!Number.isFinite(target) || target < 0) return;
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === taskId && hasTaskCounter(t) && !t.trashed ? { ...t, counterTarget: target } : t)) }));
  },

  toggleCtxTag: (taskId, tag) => {
    set((s) => ({
      tasks: s.tasks.map((t) => {
        if (t.id !== taskId) return t;
        const tags = t.ctxTags || [];
        const i = tags.indexOf(tag);
        return { ...t, ctxTags: i < 0 ? [...tags, tag] : tags.filter((_, idx) => idx !== i) };
      }),
    }));
  },

  saveTask: (id, patch) => {
    const isNew = id == null;
    let taskId = id as number;

    if (isNew) {
      const created = get().createTask(patch.title, patch.type, null, '');
      taskId = created.id;
    }

    set((s) => ({
      tasks: s.tasks.map((t) => {
        if (t.id !== taskId) return t;
        const next: Task = { ...t, title: patch.title.trim() || t.title };

        if (patch.type !== next.type) {
          next.type = patch.type;
          if (patch.type === 'check' && !next.items) next.items = [];
          if (patch.type === 'counter' && next.counter === undefined) {
            next.counter = 0;
            next.counterTarget = 10;
          }
          if (patch.type === 'alarm' && !next.alarmTime) next.alarmTime = '';
          if (patch.type === 'note' && !next.note) next.note = '';
          if (patch.type === 'ctx' && !next.ctxTags) next.ctxTags = [];
          if (patch.type === 'negative') next.negXp = 5;
        }

        let zid = patch.zoneId ?? null;
        if (patch.type === 'zonelinked' && !zid) {
          const cz = get().getActiveZones(nowHM());
          if (cz[0] && cz[0].id !== 0) zid = cz[0].id;
        }
        const zone = zid ? get().zones.find((z) => z.id === zid) : null;
        next.zoneId = zid;
        next.zoneColor = zone ? zone.color : null;
        next.zoneName = zone ? zone.nm : null;
        next.folderId = patch.folderId ?? null;
        if ('planDate' in patch) next.planDate = patch.planDate || undefined;
        if (next.type !== t.type || next.planDate !== t.planDate) next.snoozeUntil = 0;
        if (next.type !== 'sched') { next.schedDate = undefined; next.schedTime = undefined; }
        next.repeat = patch.repeat || 'none';
        next.repeatDays = patch.repeatDays ? patch.repeatDays.slice() : next.repeatDays;
        if (next.type !== t.type || next.repeat !== t.repeat || next.zoneId !== t.zoneId || next.planDate !== t.planDate || JSON.stringify(next.repeatDays) !== JSON.stringify(t.repeatDays)) {
          next.alarmFired = false; next.firedSched = false; next.firedPre = false;
          next.zoneOccurrenceKey = undefined;
        }
        if (next.type !== 'alarm') next.alarmTime = undefined;
        if (next.type !== 'timewin') { next.windowStart = undefined; next.windowEnd = undefined; }
        if (next.repeat !== 'interval') { next.repeatMs = undefined; next.repeatInterval = undefined; next.repeatUnit = undefined; next.nextRepeatAt = 0; }

        if (next.type === 'sched') {
          next.schedDate = patch.schedDate || '';
          next.schedTime = normalizeTime(patch.schedTime) || '';
          next.planDate = next.schedDate || undefined;
          if (next.schedDate !== t.schedDate || next.schedTime !== t.schedTime || (patch.reminderEnabled !== false) !== (t.reminderEnabled !== false) || patch.remindBeforeMinutes !== t.remindBeforeMinutes) {
            next.firedSched = false;
            next.firedPre = false;
            next.snoozeUntil = 0;
          }
        }
        if ('reminderEnabled' in patch) next.reminderEnabled = patch.reminderEnabled;
        if ('remindBeforeMinutes' in patch) next.remindBeforeMinutes = patch.remindBeforeMinutes;
        if (next.type === 'timewin') {
          next.windowStart = patch.windowStart || '07:00';
          next.windowEnd = patch.windowEnd || '09:00';
        }
        next.completedToday = next.type === 'timewin' ? next.done : undefined;
        next.zoneDoneToday = next.type === 'zonelinked' ? next.done : undefined;
        if (next.repeat === 'interval') {
          next.repeatInterval = patch.repeatInterval || 30;
          next.repeatUnit = patch.repeatUnit || 'min';
          next.repeatMs = (REPEAT_UNIT_MS[next.repeatUnit] || 60000) * next.repeatInterval;
          if (next.repeatMs !== t.repeatMs || t.repeat !== next.repeat) next.nextRepeatAt = next.done ? Date.now() + next.repeatMs : 0;
        }
        if (next.type === 'alarm') {
          next.alarmTime = normalizeTime(patch.alarmTime) || '';
          if (next.alarmTime !== t.alarmTime) { next.alarmFired = false; next.snoozeUntil = 0; }
        }
        if ('note' in patch) next.note = patch.note;
        next.counterEnabled = next.type === 'counter' || (next.type !== 'negative' && (patch.counterEnabled ?? (t.type !== 'counter' && t.counterEnabled === true)));
        if (hasTaskCounter(next)) {
          next.counter = next.counter || 0;
          next.counterTarget = Number.isFinite(patch.counterTarget) ? Math.max(0, patch.counterTarget!) : taskCounterTarget(next);
          next.counterStep = Number.isSafeInteger(patch.counterStep) && patch.counterStep! > 0 ? patch.counterStep : next.counterStep || 1;
          next.counterUnit = patch.counterUnit || next.counterUnit || 'count';
        }
        if (next.type === 'negative') next.negXp = patch.negXp || 5;
        next.tags = (patch.tags || []).map((s) => s.trim()).filter(Boolean);

        if (next.type === 'check' && (!t.items || t.items.length === 0) && Array.isArray(patch.items)) {
          next.items = patch.items
            .filter(item => typeof item?.text === 'string' && item.text.trim())
            .map(item => ({ text: item.text.trim(), done: false, credited: false }));
        }

        if (isNew && next.type === 'check') next.expanded = true;

        if (next.type !== t.type || next.zoneId !== t.zoneId || next.planDate !== t.planDate
          || next.repeat !== t.repeat || JSON.stringify(next.repeatDays) !== JSON.stringify(t.repeatDays)
          || (next.repeat === 'interval' && t.repeat === 'interval'
            && (next.repeatMs !== t.repeatMs || next.repeatInterval !== t.repeatInterval || next.repeatUnit !== t.repeatUnit))
          || next.schedDate !== t.schedDate || next.schedTime !== t.schedTime
          || next.alarmTime !== t.alarmTime || next.windowStart !== t.windowStart || next.windowEnd !== t.windowEnd) {
          next.questDeferral = undefined;
          next.completionDeferral = undefined;
        }

        return next;
      }),
    }));

    return taskId;
  },

  snoozeTask: (id, x, requestedAt) => {
    set((s) => ({
      tasks: s.tasks.map((t) => {
        if (t.id !== id) return t;
        // The editor and Android snapshot use minute precision. Round forward
        // so the saved time is never earlier than the requested delay.
        const base = Number.isFinite(requestedAt) ? requestedAt! : Date.now();
        const d = new Date(x === 'tomorrow' ? base : Math.ceil((base + x * 60000) / 60000) * 60000);
        if (x === 'tomorrow') d.setDate(d.getDate() + 1);
        const day = fmtDate(d);
        const time = x === 'tomorrow' ? normalizeTime(t.type === 'alarm' ? t.alarmTime : t.schedTime) : hmToString({ h: d.getHours(), m: d.getMinutes() });
        const patch: Partial<Task> = {
          planDate: day, snoozeUntil: x === 'tomorrow' ? 0 : d.getTime(), done: false,
          completionCredited: t.completionCredited ?? t.done,
          completionCreditDate: (t.completionCredited ?? t.done) ? (t.completionCreditDate || t.doneDate || fmtDate(new Date())) : t.completionCreditDate,
          doneDate: undefined, doneAt: undefined, nextRepeatAt: 0,
          completedToday: t.type === 'timewin' ? false : t.completedToday,
          zoneDoneToday: t.type === 'zonelinked' ? false : t.zoneDoneToday,
          firedSched: false, firedPre: false, alarmFired: false,
          questDeferral: undefined, completionDeferral: undefined,
        };
        if (t.type === 'sched') { patch.schedDate = day; patch.schedTime = time || t.schedTime; }
        if (t.type === 'alarm' && time) patch.alarmTime = time;
        return { ...t, ...patch };
      }),
      xpPopup: { text: '⏳ Відкладено', tone: 'muted' },
    }));
  },

  deferTask: (id, kind, requestedAt, explicitDeferral) => {
    const task = get().tasks.find(candidate => candidate.id === id);
    if (!task || task.trashed || task.done || task.someday) return;
    const baseAt = Number.isFinite(requestedAt) ? requestedAt! :
      (explicitDeferral && Number.isFinite(explicitDeferral.requestedAt) ? explicitDeferral.requestedAt : Date.now());
    let deferral: QuestDeferral;
    if (explicitDeferral && isValidQuestDeferral(explicitDeferral) && explicitDeferral.kind === kind) {
      deferral = explicitDeferral;
    } else {
      const now = new Date(baseAt);
      const timeline = buildQuestTimeline({
        zones: get().zones,
        planDayOff: get().planDayOff,
        dayBlocks: day => get().dayBlocks(day),
        now,
      });
      deferral = createQuestDeferral(task, kind, { requestedAt: baseAt, timeline, zones: get().zones });
    }
    set(state => ({
      tasks: state.tasks.map(candidate => candidate.id === id
        ? { ...candidate, occurrenceId: taskOccurrenceId(candidate, baseAt, candidate.zoneId
          ? get().getZoneOccurrenceKey(candidate.zoneId, new Date(baseAt)) : undefined),
          questDeferral: deferral, completionDeferral: undefined, snoozeUntil: 0, firedSched: false, firedPre: false, alarmFired: false }
        : candidate),
      alarmTaskIds: state.alarmTaskIds.filter(taskId => taskId !== id),
      xpPopup: { text: deferral.kind === 'next-zone' ? '⏳ До наступної зони' : '⏳ Не сьогодні', tone: 'muted' },
    }));
    get().showToast(deferral.kind === 'next-zone' ? 'Повернеться у наступній зоні' : 'Повернеться пізніше');
  },

  updateNote: (id, text) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, note: text } : t)) }));
  },

  updateAlarm: (id, time) => {
    const normalized = normalizeTime(time);
    const alarmTime = normalized || '';
    set((s) => ({ tasks: s.tasks.map((t) => {
      if (t.id !== id || t.trashed || t.type !== 'alarm') return t;
      if ((normalizeTime(t.alarmTime) || '') === alarmTime) return t;
      return { ...t, alarmTime, alarmFired: false, snoozeUntil: 0, questDeferral: undefined, completionDeferral: undefined };
    }) }));
  },

  removeTag: (id, index) => {
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, tags: t.tags.filter((_, i) => i !== index) } : t)),
    }));
  },
});
