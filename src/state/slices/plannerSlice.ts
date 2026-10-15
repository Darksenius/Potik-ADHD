import { normalizeTime, normalizeDayRange } from '../../utils/date';
import type { AppSlice } from '../store';
import type {
  DateKey,
  PlanItem,
  PlanRule,
  DayLogEntry,
  PlanDayZoneOverride,
  DayTemplate,
  DayTemplateTask,
  WeekTemplate,
} from '../../types';
import { fmtDate, addDaysDs, isDateKey } from '../../utils/date';
import { ruleFor, ruleEntry, dayBlocksFor, dayIsRestFor } from './plannerHelpers';
import { ULBL } from './routineSlice';
import { COUNTER_UNITS, hasTaskCounter } from '../../utils/taskCounter';
import { completedTaskOccurrences } from '../../utils/taskHistory';
import { appendDayPresetTasks, sanitizeDayPresetTask } from '../../utils/dayPresetTasks';

/** getWeekStart() — рядки 3316–3319. Понеділок тижня зі зсувом `off` (у тижнях). */
export function getWeekStart(off: number): Date {
  const now = new Date();
  const day = now.getDay();
  const diff = day === 0 ? -6 : 1 - day;
  const m = new Date(now);
  m.setDate(now.getDate() + diff + off * 7);
  m.setHours(0, 0, 0, 0);
  return m;
}

export interface DayStats {
  tasksDone: number;
  taskTitles: string[];
  notes: number;
  focusSwitches: number;
  focusList: string[];
  energy: number;
  routineDone: Record<string, string | boolean>;
}

function freezeAppliedDayTemplateBlocks(
  templateId: string,
  planDayZones: Record<DateKey, PlanDayZoneOverride[]>,
  planRules: PlanRule[],
  dayTemplates: DayTemplate[]
): Record<DateKey, PlanDayZoneOverride[]> {
  let frozen = planDayZones;
  for (const rule of planRules) {
    if (!isDateKey(rule.start) || !isDateKey(rule.end) || rule.end < rule.start) continue;
    for (let ds = rule.start; isDateKey(ds) && ds <= rule.end;) {
      if (!planDayZones[ds] && ruleFor(planRules, ds)?.id === rule.id && ruleEntry(rule, ds)?.tplId === templateId) {
        if (frozen === planDayZones) frozen = { ...planDayZones };
        frozen[ds] = dayBlocksFor(ds, planDayZones, planRules, dayTemplates).map((block) => ({ ...block }));
      }
      if (ds === rule.end) break;
      const next = addDaysDs(ds, 1);
      if (!isDateKey(next) || next <= ds) break;
      ds = next;
    }
  }
  return frozen;
}

export interface PlannerSlice {
  planItems: Record<DateKey, PlanItem[]>;
  planRestDays: Record<DateKey, boolean>;
  planSchedules: Record<DateKey, string>;
  planDayZones: Record<DateKey, PlanDayZoneOverride[]>;
  planDayOff: Record<DateKey, number[]>;
  planRules: PlanRule[];
  planDayLog: Record<DateKey, DayLogEntry>;
  dayTemplates: DayTemplate[];
  weekTemplates: WeekTemplate[];
  weekTplSeeded: boolean;

  /** dayBlocks(ds) — рядки 3343–3349 */
  dayBlocks: (ds: DateKey) => PlanDayZoneOverride[];
  /** dayIsRest(ds) — рядки 3351–3355 */
  dayIsRest: (ds: DateKey) => boolean;
  /** materializeDay(ds) — рядки 3357–3360, copy-on-write перед ручною правкою */
  materializeDay: (ds: DateKey) => PlanDayZoneOverride[];

  /** setDayNote(ds,val) — рядки 3611–3616 */
  setDayNote: (ds: DateKey, text: string) => void;
  /** saveDayZone() — рядки 3617–3624, параметризовано (без читання DOM) */
  addDayZone: (ds: DateKey, zoneId: number, s: string, e: string) => void;
  /** rmDayZone(i) — рядки 3625–3631 */
  removeDayZone: (ds: DateKey, index: number) => void;
  /** dayResetToRule() — рядки 3633–3637 */
  resetDayToRule: (ds: DateKey) => void;
  /** dayOffDaily(zid) — рядки 3639–3644 */
  setDailyZoneOff: (ds: DateKey, zoneId: number) => void;
  /** dayRestoreDaily(zid) — рядки 3645–3650 */
  restoreDailyZone: (ds: DateKey, zoneId: number) => void;
  /** dayEditDaily(zid) — рядки 3653–3661 */
  editDailyZoneForDay: (ds: DateKey, zoneId: number) => void;

  /** Save or update an authored day preset without binding it to a real date. */
  saveDayTemplate: (template: Omit<DayTemplate, 'id'> & { id?: string }) => void;
  /** applyDayTemplate(id) — рядки 3672–3677 */
  applyDayTemplate: (ds: DateKey, templateId: string) => void;
  /** delDayTemplate(id) — рядки 3678–3681 */
  deleteDayTemplate: (templateId: string) => void;

  /** planAddItem() — рядки 3885–3904, без kbdOpen (текст приходить від компонента) */
  addPlanItem: (ds: DateKey, rawText: string) => void;
  updateDayZone: (ds: DateKey, index: number, start: string, end: string) => void;
  /** planAddEvent() — рядки 3905–3918 */
  addPlanEvent: (ds: DateKey, rawText: string) => void;
  /** planTogRest() — рядки 3860–3870 */
  toggleRestDay: (ds: DateKey) => void;
  /** planTog(i) — рядок 3881 */
  togglePlanItem: (ds: DateKey, index: number) => void;
  /** planRm(i) — рядок 3882 */
  removePlanItem: (ds: DateKey, index: number) => void;

  /** seedWeekTemplates() — рядки 3689–3716 */
  seedWeekTemplates: () => void;
  /** wkcSave() — рядки 3793–3805, без DOM-читання назви (приходить параметром) */
  saveWeekTemplate: (template: WeekTemplate) => void;
  /** wkcDel(id) — рядки 3760–3767, без confirm() (питає компонент) */
  deleteWeekTemplate: (id: string) => void;
  /** wkcDoApply() — рядки 3823–3841, без confirm()/DOM (параметри приходять від компонента) */
  applyWeekTemplate: (templateId: string, startDs: DateKey, spanDays: number) => void;
  /** wkcRuleDel(i) — рядки 3842–3848 */
  deleteRule: (index: number) => void;

  /** dayStats(ds) — рядки 3444–3452, чиста аналітика (читає tasks/qnotes/focusLog з інших slice-ів) */
  dayStats: (ds: DateKey) => DayStats;
  /** logDayData() — рядки 3410–3421, викликати з setInterval(...,5*60*1000) у main.tsx */
  logDayData: () => void;
  /** setPlanSched(el) — рядки 3433–3437. Проста мітка графіка дня (9-18/Remote/…), НЕ dayTemplates. */
  setPlanSchedule: (ds: DateKey, templateLabelId: string) => void;
}

export const createPlannerSlice: AppSlice<PlannerSlice> = (set, get) => ({
  planItems: {},
  planRestDays: {},
  planSchedules: {},
  planDayZones: {},
  planDayOff: {},
  planRules: [],
  planDayLog: {},
  dayTemplates: [],
  weekTemplates: [],
  weekTplSeeded: false,

  dayBlocks: (ds) => {
    const { planDayZones, planRules, dayTemplates } = get();
    return dayBlocksFor(ds, planDayZones, planRules, dayTemplates);
  },

  dayIsRest: (ds) => {
    const { planRestDays, planRules } = get();
    return dayIsRestFor(ds, planRestDays, planRules);
  },

  materializeDay: (ds) => {
    const existing = get().planDayZones[ds];
    if (existing) return existing;
    const blocks = get().dayBlocks(ds).slice();
    set((s) => ({ planDayZones: { ...s.planDayZones, [ds]: blocks } }));
    return blocks;
  },

  setDayNote: (ds, text) => {
    set((s) => ({
      planDayLog: {
        ...s.planDayLog,
        [ds]: { ...(s.planDayLog[ds] || { focus: [], energy: 0, routineDone: {}, priorities: [], tasksDone: 0 }), note: text },
      },
    }));
    if (text.trim()) get().recordProgressDay();
  },

  addDayZone: (ds, zoneId, s, e) => {
    const range = normalizeDayRange(s, e);
    if (!range || !get().zones.some(zone => zone.id === zoneId)) return;
    const blocks = get().materializeDay(ds).concat([{ zoneId, ...range }]);
    set((st) => ({ planDayZones: { ...st.planDayZones, [ds]: blocks } }));
    get().recalculateZoneUsage();
    get().recordProgressDay();
  },
  updateDayZone: (ds, index, start, end) => {
    const range = normalizeDayRange(start, end);
    if (!range) return;
    const blocks = get().materializeDay(ds).map((b, i) => i === index ? { ...b, ...range, ov: true } : b);
    set(st => ({ planDayZones: { ...st.planDayZones, [ds]: blocks } }));
    get().recalculateZoneUsage();
    get().recordProgressDay();
  },

  removeDayZone: (ds, index) => {
    const blocks = get().materializeDay(ds).slice();
    blocks.splice(index, 1);
    set((st) => ({ planDayZones: { ...st.planDayZones, [ds]: blocks } }));
    get().recalculateZoneUsage();
  },

  resetDayToRule: (ds) => {
    set((s) => {
      const planDayZones = { ...s.planDayZones };
      const planRestDays = { ...s.planRestDays };
      const planDayOff = { ...s.planDayOff };
      delete planDayZones[ds];
      delete planRestDays[ds];
      delete planDayOff[ds];
      return { planDayZones, planRestDays, planDayOff };
    });
    get().recalculateZoneUsage();
  },

  setDailyZoneOff: (ds, zoneId) => {
    set((s) => {
      const cur = s.planDayOff[ds] || [];
      if (cur.indexOf(zoneId) >= 0) return s;
      return { planDayOff: { ...s.planDayOff, [ds]: [...cur, zoneId] } };
    });
  },

  restoreDailyZone: (ds, zoneId) => {
    set((s) => {
      const cur = s.planDayOff[ds];
      if (!cur) return s;
      const next = cur.filter((id) => id !== zoneId);
      const planDayOff = { ...s.planDayOff };
      if (next.length) planDayOff[ds] = next;
      else delete planDayOff[ds];
      return { planDayOff };
    });
  },

  editDailyZoneForDay: (ds, zoneId) => {
    const z = get().zones.find((zz) => zz.id === zoneId);
    if (!z) return;
    const blocks = get()
      .materializeDay(ds)
      .concat((z.slots || []).map((sl) => ({ zoneId, s: sl.s, e: sl.e, ov: true })));
    set((s) => ({
      planDayZones: { ...s.planDayZones, [ds]: blocks },
      planDayOff: { ...s.planDayOff, [ds]: [...(s.planDayOff[ds] || []).filter((id) => id !== zoneId), zoneId] },
    }));
    get().recalculateZoneUsage();
  },

  saveDayTemplate: (draft) => {
    const name = (draft.name || '').trim();
    if (!name) return;
    set((s) => {
      const existingIndex = draft.id ? s.dayTemplates.findIndex((template) => template.id === draft.id) : -1;
      const id = existingIndex >= 0 ? draft.id! : (draft.id || (() => {
        const base = `tpl${Date.now()}`;
        let candidate = base;
        let suffix = 1;
        while (s.dayTemplates.some((template) => template.id === candidate)) candidate = `${base}-${suffix++}`;
        return candidate;
      })());
      const template: DayTemplate = {
        id,
        name,
        blocks: (draft.blocks || []).filter((block) => Number.isSafeInteger(block.zoneId) && !!block.s && !!block.e)
          .map((block) => ({ zoneId: block.zoneId, s: block.s, e: block.e })),
        tasks: (() => {
          const result: DayTemplateTask[] = [];
          const usedIds = new Set<string>();
          (draft.tasks || []).forEach((entry) => {
            let safe = sanitizeDayPresetTask(entry);
            if (!safe) return;
            let id = safe.id;
            let suffix = 1;
            while (usedIds.has(id)) id = `${safe.id}-${suffix++}`;
            if (id !== safe.id) safe = { ...safe, id };
            usedIds.add(id);
            result.push(safe);
          });
          return result;
        })(),
      };
      let planDayZones = s.planDayZones;
      if (existingIndex >= 0) {
        planDayZones = freezeAppliedDayTemplateBlocks(id, s.planDayZones, s.planRules, s.dayTemplates);
        const dayTemplates = s.dayTemplates.slice();
        dayTemplates[existingIndex] = template;
        return { dayTemplates, planDayZones };
      }
      return { dayTemplates: [...s.dayTemplates, template] };
    });
    get().recalculateZoneUsage();
    get().recordProgressDay();
  },

  applyDayTemplate: (ds, templateId) => {
    if (!isDateKey(ds)) {
      get().showToast('Вибери коректну дату для застосування пресету.');
      return;
    }
    const tp = get().dayTemplates.find((x) => x.id === templateId);
    if (!tp) return;
    const blocks = (tp.blocks || []).map((b) => ({ zoneId: b.zoneId, s: b.s, e: b.e }));
    set((s) => {
      const appended = appendDayPresetTasks(s.tasks, s.nid, tp.id, tp.tasks || [], ds, s.zones);
      return {
        planDayZones: { ...s.planDayZones, [ds]: blocks },
        tasks: appended.tasks,
        nid: appended.nid,
      };
    });
    get().recalculateZoneUsage();
    get().recordProgressDay();
  },

  deleteDayTemplate: (templateId) => {
    set((s) => ({
      dayTemplates: s.dayTemplates.filter((x) => x.id !== templateId),
      planDayZones: freezeAppliedDayTemplateBlocks(templateId, s.planDayZones, s.planRules, s.dayTemplates),
    }));
    get().recalculateZoneUsage();
  },

  addPlanItem: (ds, rawText) => {
    const m = rawText.match(/^(\d{1,2}:\d{2})\s+(.+)/);
    let time = '';
    let title = rawText;
    if (m) {
      time = m[1];
      title = m[2];
    }
    const dd: DateKey = ds;
    if (time) {
      time = normalizeTime(time) || '';
      if (!time) return;
      const task = get().createTask(title, 'sched', null, '');
      set((s) => ({
        tasks: s.tasks.map((t) => (t.id === task.id ? { ...t, schedDate: dd, schedTime: time, firedSched: false, planDate: dd, reminderEnabled: true, remindBeforeMinutes: 0 } : t)),
      }));
    } else {
      const task = get().createTask(title, 'simple', null, '');
      set((s) => ({ tasks: s.tasks.map((t) => (t.id === task.id ? { ...t, planDate: dd } : t)) }));
    }
    get().award(2);
  },

  addPlanEvent: (ds, rawText) => {
    const m = rawText.match(/^(\d{1,2}:\d{2})\s+(.+)/);
    let time = '';
    let title = rawText;
    if (m) {
      time = m[1];
      title = m[2];
    }
    const item: PlanItem = { title, done: false, isEvent: true, time };
    set((s) => ({ planItems: { ...s.planItems, [ds]: [...(s.planItems[ds] || []), item] } }));
  },

  toggleRestDay: (ds) => {
    const nextRest = !get().dayIsRest(ds);
    set((s) => ({ planRestDays: { ...s.planRestDays, [ds]: nextRest } }));
    if (nextRest && !(get().planItems[ds] || []).some(i => i.isPrompt && i.title.includes('Заплануй наступний тиждень'))) {
      set((s) => ({
        planItems: { ...s.planItems, [ds]: [...(s.planItems[ds] || []), { title: 'Заплануй наступний тиждень', done: false, isPrompt: true }] },
      }));
    }
  },

  togglePlanItem: (ds, index) => {
    const items = get().planItems[ds];
    if (!items || !items[index] || items[index].isPrompt) return;
    const willBeDone = !items[index].done;
    set((s) => ({
      planItems: { ...s.planItems, [ds]: s.planItems[ds].map((it, i) => (i === index ? { ...it, done: willBeDone } : it)) },
    }));
  },

  removePlanItem: (ds, index) => {
    const items = get().planItems[ds];
    if (!items) return;
    const next = items.slice();
    next.splice(index, 1);
    set((s) => ({ planItems: { ...s.planItems, [ds]: next } }));
  },

  seedWeekTemplates: () => {
    if (get().weekTplSeeded) return;
    const zid = (nm: string) => get().zones.find((z) => z.nm === nm)?.id ?? null;
    const mkBlocks = (list: [string, string, string][]) => {
      const out: { zoneId: number; s: string; e: string }[] = [];
      list.forEach(([nm, s, e]) => {
        const id = zid(nm);
        if (id != null) out.push({ zoneId: id, s, e });
      });
      return out;
    };

    const dayTemplates = get().dayTemplates.slice();
    if (!dayTemplates.some((t) => t.id === 'tplWork')) {
      const wb = mkBlocks([
        ['Сон', '00:00', '06:00'], ['Ранок', '06:00', '09:00'], ['Робота', '09:00', '13:00'],
        ['Обід', '13:00', '14:00'], ['Робота', '14:00', '18:00'], ['Вечір', '18:00', '22:00'], ['Сон', '22:00', '24:00'],
      ]);
      if (wb.length) dayTemplates.push({ id: 'tplWork', name: 'Робочий день', blocks: wb });
    }
    if (!dayTemplates.some((t) => t.id === 'tplRest')) {
      const rb = mkBlocks([['Сон', '00:00', '08:00'], ['Ранок', '08:00', '11:00'], ['Вечір', '18:00', '23:00'], ['Сон', '23:00', '24:00']]);
      if (rb.length) dayTemplates.push({ id: 'tplRest', name: 'Вихідний', blocks: rb });
    }

    let weekTemplates = get().weekTemplates;
    if (!weekTemplates.length) {
      const W = { tplId: 'tplWork', rest: false };
      const R = { tplId: 'tplRest', rest: true };
      const c = <T,>(x: T): T => JSON.parse(JSON.stringify(x));
      weekTemplates = [
        { id: 'wk52', name: '5/2', days: [c(W), c(W), c(W), c(W), c(W), c(R), c(R)] },
        { id: 'wk22', name: '2/2', days: [c(W), c(W), c(R), c(R)] },
        { id: 'wk33', name: '3/3', days: [c(W), c(W), c(W), c(R), c(R), c(R)] },
      ];
    }

    set({ dayTemplates, weekTemplates, weekTplSeeded: true });
  },

  saveWeekTemplate: (template) => {
    const name = (template.name || '').trim();
    if (!name) return;
    const tpl = { ...template, name };
    set((s) => {
      if (tpl.id) {
        const i = s.weekTemplates.findIndex((x) => x.id === tpl.id);
        if (i >= 0) {
          const weekTemplates = s.weekTemplates.slice();
          weekTemplates[i] = tpl;
          return { weekTemplates };
        }
      }
      return { weekTemplates: [...s.weekTemplates, { ...tpl, id: tpl.id || 'wk' + Date.now() }] };
    });
  },

  deleteWeekTemplate: (id) => {
    set((s) => ({ weekTemplates: s.weekTemplates.filter((x) => x.id !== id) }));
  },

  applyWeekTemplate: (templateId, startDs, spanDays) => {
    const tp = get().weekTemplates.find((x) => x.id === templateId);
    if (!tp || !((tp.days as unknown[]) || []).length) return;
    if (!isDateKey(startDs)) {
      get().showToast('Вибери коректну дату початку графіка.');
      return;
    }
    if (!Number.isSafeInteger(spanDays) || spanDays < 1 || spanDays > 3660) {
      get().showToast('Період графіка має бути від 1 до 3660 днів.');
      return;
    }
    const endDs = addDaysDs(startDs, spanDays - 1);
    if (!isDateKey(endDs)) {
      get().showToast('Дата завершення графіка виходить за межі календаря.');
      return;
    }

    set((s) => {
      const planDayZones = { ...s.planDayZones };
      const planRestDays = { ...s.planRestDays };
      const planDayOff = { ...s.planDayOff };
      for (let i = 0; i < spanDays; i++) {
        const ds = addDaysDs(startDs, i);
        delete planDayZones[ds];
        delete planRestDays[ds];
        delete planDayOff[ds];
      }
      const planRules = s.planRules.filter((r) => !(r.start >= startDs && r.end <= endDs));
      const appliedRule = {
        id: 'pr' + Date.now(),
        name: tp.name || '',
        start: startDs,
        end: endDs,
        days: JSON.parse(JSON.stringify(tp.days)),
      };
      planRules.push(appliedRule);
      let tasks = s.tasks;
      let nid = s.nid;
      for (let i = 0; i < spanDays; i++) {
        const ds = addDaysDs(startDs, i);
        const scheduledTemplateId = ruleEntry(appliedRule, ds)?.tplId;
        if (!scheduledTemplateId) continue;
        const dayTemplate = s.dayTemplates.find((template) => template.id === scheduledTemplateId);
        if (!dayTemplate?.tasks?.length) continue;
        const appended = appendDayPresetTasks(tasks, nid, dayTemplate.id, dayTemplate.tasks, ds, s.zones);
        tasks = appended.tasks;
        nid = appended.nid;
      }
      return { planDayZones, planRestDays, planDayOff, planRules, tasks, nid };
    });
    get().recalculateZoneUsage();
    get().recordProgressDay();
  },

  deleteRule: (index) => {
    set((s) => {
      const planRules = s.planRules.slice();
      planRules.splice(index, 1);
      return { planRules };
    });
    get().recalculateZoneUsage();
  },

  dayStats: (ds) => {
    const s = get();
    const doneTasks = completedTaskOccurrences(s.taskHistory, s.tasks).filter(event => event.date === ds);
    const notes = s.qnotes.filter((n) => n.date === ds);
    const foci = (s.focusLog || []).filter((f) => f.date === ds);
    const distinct: string[] = [];
    foci.forEach((f) => {
      if (distinct.indexOf(f.val) < 0) distinct.push(f.val);
    });
    const log = s.planDayLog[ds] || ({} as Partial<DayLogEntry>);
    return {
      tasksDone: doneTasks.length,
      taskTitles: doneTasks.map((t) => t.title),
      notes: notes.length,
      focusSwitches: foci.length,
      focusList: distinct,
      energy: log.energy || 0,
      routineDone: log.routineDone || {},
    };
  },

  logDayData: () => {
    const s = get();
    const ds = fmtDate(new Date());
    const existing = s.planDayLog[ds] || { focus: [], energy: 0, routineDone: {}, priorities: [], tasksDone: 0 };
    const routineDone: Record<string, string | boolean> = {};
    s.recur.forEach((r) => {
      routineDone[r.nm] = r.unit === 'check' ? r.done : r.val + ' ' + (ULBL[r.unit] || '');
    });
    s.tasks.filter(task => !task.trashed && !task.someday && task.repeat !== 'none').forEach(task => {
      if (hasTaskCounter(task)) routineDone[task.title] = `${task.counter || 0} ${COUNTER_UNITS[task.counterUnit || 'count']}`;
      else if (task.type === 'negative') routineDone[task.title] = `${task.counter || 0} раз`;
      else routineDone[task.title] = task.done;
    });
    const focus = existing.focus.slice();
    if (s.currentFocus && (!focus.length || focus[focus.length - 1] !== s.currentFocus)) {
      focus.push(s.currentFocus);
    }
    set((st) => ({
      planDayLog: {
        ...st.planDayLog,
        [ds]: {
          ...existing,
          energy: s.energy,
          tasksDone: completedTaskOccurrences(s.taskHistory, s.tasks).filter(event => event.date === ds).length,
          priorities: s.priorities.slice(),
          routineDone,
          focus,
        },
      },
    }));
  },

  setPlanSchedule: (ds, templateLabelId) => {
    set((s) => ({ planSchedules: { ...s.planSchedules, [ds]: templateLabelId } }));
  },
});

export { ruleFor };
