import type { AppSlice } from '../store';
import type { Task, TaskType, ChecklistItem } from '../../types';
import { fmtDate, nowHM, normalizeTime, hmToString } from '../../utils/date';

/** TXP — рядок 1239: базова нагорода XP за тип задачі при виконанні. */
const TASK_XP: Record<TaskType, number> = {
  simple: 10, check: 10, counter: 2, note: 5, alarm: 10, sched: 10,
  timewin: 5, ctx: 8, negative: 0, zonelinked: 8,
};

const REPEAT_UNIT_MS: Record<string, number> = { sec: 1000, min: 60000, hour: 3600000, day: 86400000, week: 604800000, month: 2592000000 };
const TRASH_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export interface TasksSlice {
  tasks: Task[];
  /** Наступний вільний id (було S.nid) */
  nid: number;

  createTask: (title: string, type: TaskType, zoneId?: number | null, alarmTime?: string) => Task;
  toggleTask: (id: number) => void;
  skipTask: (id: number) => void;
  toSomeday: (id: number) => void;
  fromSomeday: (id: number) => void;
  addQuickTaskFromShade: (title: string) => void;
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
  changeCounter: (taskId: number, delta: number) => void;
  updateCounterTarget: (taskId: number, target: number) => void;
  toggleCtxTag: (taskId: number, tag: string) => void;
  toggleZoneDoneTask: (taskId: number) => void;

  saveTask: (id: number | null, patch: Partial<Task> & { title: string; type: TaskType }) => number;
  /** efSnooze(x) — рядки 2402–2411. x='tomorrow' або хвилини. */
  snoozeTask: (id: number, x: 'tomorrow' | number) => void;
  /** updNote(tid,v) — рядок 2250 */
  updateNote: (id: number, text: string) => void;
  /** updAlarm(tid,v) — рядок 2251 */
  updateAlarm: (id: number, time: string) => void;
  /** rmTag(tid,i) — рядок 2259 */
  removeTag: (id: number, index: number) => void;
}

export const createTasksSlice: AppSlice<TasksSlice> = (set, get) => ({
  tasks: [],
  nid: 300,

  createTask: (title, type, zoneId, alarmTime) => {
    const nid = get().nid;
    const zone = zoneId ? get().zones.find((z) => z.id === zoneId) : null;
    const task: Task = {
      id: nid,
      title,
      type,
      done: false,
      someday: false,
      zoneId: zoneId ?? null,
      zoneColor: zone ? zone.color : null,
      zoneName: zone ? zone.nm : null,
      expanded: false,
      created: new Date().toISOString(),
      items: type === 'check' ? [] : undefined,
      counter: type === 'counter' || type === 'negative' ? 0 : undefined,
      counterTarget: type === 'counter' ? 10 : undefined,
      negXp: type === 'negative' ? 5 : undefined,
      note: type === 'note' ? '' : undefined,
      alarmTime: type === 'alarm' ? alarmTime || '' : undefined,
      alarmFired: false,
      schedDate: type === 'sched' ? '' : undefined,
      schedTime: type === 'sched' ? '' : undefined,
      firedSched: type === 'sched' ? false : undefined,
      firedPre: type === 'sched' ? false : undefined,
      windowStart: type === 'timewin' ? '07:00' : undefined,
      windowEnd: type === 'timewin' ? '09:00' : undefined,
      completedToday: type === 'timewin' ? false : undefined,
      ctxTags: type === 'ctx' ? [] : undefined,
      zoneDoneToday: type === 'zonelinked' ? false : undefined,
      repeat: 'none',
      repeatDays: [false, false, false, false, false, false, false],
      tags: [],
    };
    set((s) => ({ tasks: [task, ...s.tasks], nid: s.nid + 1 }));
    return task;
  },

  toggleTask: (id) => {
    const t = get().tasks.find((x) => x.id === id);
    if (!t) return;

    if (t.type === 'negative') {
      set((s) => ({
        tasks: s.tasks.map((x) => (x.id === id ? { ...x, counter: (x.counter || 0) + 1 } : x)),
      }));
      get().incrementNegCount();
      get().award(-(t.negXp || 5));
      return;
    }

    const willBeDone = !t.done;
    set((s) => ({
      tasks: s.tasks.map((x) => {
        if (x.id !== id) return x;
        const updated: Task = { ...x, done: willBeDone };
        if (x.type === 'timewin' && willBeDone) updated.completedToday = true;
        if (willBeDone) {
          updated.doneDate = fmtDate(new Date());
          updated.doneAt = new Date().toLocaleTimeString('uk', { hour: '2-digit', minute: '2-digit' });
          updated.snoozeUntil = 0;
          if (x.repeat === 'interval' && x.repeatMs) updated.nextRepeatAt = Date.now() + x.repeatMs;
        }
        return updated;
      }),
      priorities: willBeDone ? s.priorities.map((pid) => (pid === id ? null : pid)) : s.priorities,
    }));

    if (willBeDone) {
      get().incrementDoneCount();
      get().award(TASK_XP[t.type] ?? 10);
    }
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
      tasks: s.tasks.map((x) => (x.id === id ? { ...x, someday: true, done: false } : x)),
      priorities: s.priorities.map((pid) => (pid === id ? null : pid)),
      xpPopup: { text: '→ Колись', tone: 'muted' },
    }));
  },

  fromSomeday: (id) => {
    set((s) => ({ tasks: s.tasks.map((x) => (x.id === id ? { ...x, someday: false } : x)) }));
  },

  addQuickTaskFromShade: (title) => {
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
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, trashed: true, trashedAt: Date.now() } : t)),
      priorities: s.priorities.map((pid) => (pid === id ? null : pid)),
    }));
  },

  restoreTask: (id) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, trashed: false, trashedAt: null } : t)) }));
  },

  hardDeleteTask: (id) => {
    set((s) => ({ tasks: s.tasks.filter((t) => t.id !== id) }));
  },

  purgeOldTrash: () => {
    set((s) => ({
      tasks: s.tasks.filter((t) => !t.trashed || Date.now() - (t.trashedAt || 0) < TRASH_RETENTION_MS),
    }));
  },

  duplicateTask: (id) => {
    const t = get().tasks.find((x) => x.id === id);
    if (!t) return;
    const nid = get().nid;
    const copy: Task = { ...JSON.parse(JSON.stringify(t)), id: nid, done: false, expanded: false, title: t.title + ' (копія)' };
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
    if (!t || !t.items) return;
    const willBeDone = !t.items[index].done;
    set((s) => ({
      tasks: s.tasks.map((x) =>
        x.id === taskId ? { ...x, items: (x.items || []).map((it, i) => (i === index ? { ...it, done: willBeDone } : it)) } : x
      ),
    }));
    if (willBeDone) get().award(3);
  },

  addChecklistItem: (taskId, text) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const item: ChecklistItem = { text: trimmed, done: false };
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === taskId ? { ...t, items: [...(t.items || []), item] } : t)),
    }));
  },

  changeCounter: (taskId, delta) => {
    const t = get().tasks.find((x) => x.id === taskId);
    if (!t) return;
    const next = Math.max(0, (t.counter || 0) + delta);
    const justHit = next >= (t.counterTarget || 10) && !t.cntHit;
    set((s) => ({
      tasks: s.tasks.map((x) => (x.id === taskId ? { ...x, counter: next, cntHit: justHit ? true : x.cntHit } : x)),
    }));
    if (delta > 0) get().award(2);
    if (justHit) get().award(15);
  },

  updateCounterTarget: (taskId, target) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === taskId ? { ...t, counterTarget: target || 10 } : t)) }));
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

  toggleZoneDoneTask: (taskId) => {
    const t = get().tasks.find((x) => x.id === taskId);
    if (!t) return;
    const willBeDone = !t.zoneDoneToday;
    set((s) => ({ tasks: s.tasks.map((x) => (x.id === taskId ? { ...x, zoneDoneToday: willBeDone } : x)) }));
    if (willBeDone) get().award(8);
    void get().getActiveZones(nowHM())[0];
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
          if (patch.type === 'zonelinked') next.zoneDoneToday = false;
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
          next.completedToday = false;
        }
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
        if (next.type === 'note') next.note = patch.note ?? next.note;
        if (next.type === 'counter') next.counterTarget = patch.counterTarget || 10;
        if (next.type === 'negative') next.negXp = patch.negXp || 5;
        next.tags = (patch.tags || []).map((s) => s.trim()).filter(Boolean);

        if (isNew && next.type === 'check') next.expanded = true;

        return next;
      }),
    }));

    return taskId;
  },

  snoozeTask: (id, x) => {
    set((s) => ({
      tasks: s.tasks.map((t) => {
        if (t.id !== id) return t;
        // The editor and Android snapshot use minute precision. Round forward
        // so the saved time is never earlier than the requested delay.
        const d = new Date(x === 'tomorrow' ? Date.now() : Math.ceil((Date.now() + x * 60000) / 60000) * 60000);
        if (x === 'tomorrow') d.setDate(d.getDate() + 1);
        const day = fmtDate(d);
        const time = x === 'tomorrow' ? normalizeTime(t.type === 'alarm' ? t.alarmTime : t.schedTime) : hmToString({ h: d.getHours(), m: d.getMinutes() });
        const patch: Partial<Task> = { planDate: day, snoozeUntil: x === 'tomorrow' ? 0 : d.getTime(), done: false, doneDate: undefined, doneAt: undefined, firedSched: false, firedPre: false, alarmFired: false };
        if (t.type === 'sched') { patch.schedDate = day; patch.schedTime = time || t.schedTime; }
        if (t.type === 'alarm' && time) patch.alarmTime = time;
        return { ...t, ...patch };
      }),
      xpPopup: { text: '⏳ Відкладено', tone: 'muted' },
    }));
  },

  updateNote: (id, text) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, note: text } : t)) }));
  },

  updateAlarm: (id, time) => {
    set((s) => ({ tasks: s.tasks.map((t) => (t.id === id ? { ...t, alarmTime: time, alarmFired: false } : t)) }));
    // TODO: firedA.delete(tid) — Set дедуплікації, ще не перенесено (СИСТЕМА СПОВІЩЕНЬ)
  },

  removeTag: (id, index) => {
    set((s) => ({
      tasks: s.tasks.map((t) => (t.id === id ? { ...t, tags: t.tags.filter((_, i) => i !== index) } : t)),
    }));
  },
});
