import type { DateKey, DayTemplateTask, Folder, PlanDayZoneOverride, QuickNote, Task, TaskType, Zone } from '../types';
import demoPreset from '../content/demo-preset.json' with { type: 'json' };
import { appendDayPresetTasks } from '../utils/dayPresetTasks';
import { addDaysDs, fmtDate } from '../utils/date';

const PRESET_ID = demoPreset.id;
const FOLDER_ID = demoPreset.folder.id;
const ZONE_DEFS = demoPreset.zones.map(({ key, name, color, start, end, description }) => ({
  key, nm: name, color, s: start, e: end, desc: description,
}));

type DemoTask = { id: string; title: string; type: TaskType; zone?: number; note: string; repeat?: Task['repeat']; fields?: Partial<Task> };
const zoneIndex = new Map(demoPreset.zones.map((zone, index) => [zone.key, index]));
type BundledDemoTask = (typeof demoPreset.today)[number] | (typeof demoPreset.tomorrow)[number];
const asDemoTasks = (entries: readonly BundledDemoTask[]): DemoTask[] => entries.map((task) => ({
  id: task.id, title: task.title, type: task.type as TaskType,
  ...(task.zone ? { zone: zoneIndex.get(task.zone) } : {}),
  note: task.note, repeat: task.repeat as Task['repeat'], fields: task.fields as Partial<Task> | undefined,
}));
const TODAY_TASKS = asDemoTasks(demoPreset.today);
const TOMORROW_REPEATS = asDemoTasks(demoPreset.tomorrow);

function dateLabel(day: string): string {
  return new Date(`${day}T12:00:00`).toLocaleDateString('uk-UA', { day: 'numeric', month: 'long' });
}

function entry(task: DemoTask, zoneIds: number[], day: string): DayTemplateTask {
  const taskDef: DayTemplateTask['task'] = {
    title: `Приклад · ${task.title}`, type: task.type,
    zoneId: task.zone === undefined ? undefined : zoneIds[task.zone],
    note: task.note,
    tags: ['DEMO'],
    ...task.fields,
  };
  if (task.type === 'sched') taskDef.schedTime = (task.fields?.schedTime as string) || '14:30';
  if (task.type === 'alarm') taskDef.alarmTime = (task.fields?.alarmTime as string) || '22:00';
  return { id: `${task.id}:${day}`, task: taskDef };
}

/** Add one relative, bundled introduction day while preserving the user's state. */
export function applyDemoPreset(state: {
  tasks: Task[]; taskHistory: { zoneId?: number | null }[]; nid: number; zones: Zone[]; folders: Folder[]; qnotes: QuickNote[]; qnid: number;
  planDayZones: Record<DateKey, PlanDayZoneOverride[]>; planDayOff: Record<DateKey, number[]>;
  dayTemplates: { blocks?: { zoneId: number }[]; tasks?: { task?: { zoneId?: number | null } }[] }[];
  lastZoneId?: number;
  dayBlocks: (date: DateKey) => PlanDayZoneOverride[];
}, now = new Date()) {
  const today = fmtDate(now);
  const tomorrow = addDaysDs(today, 1);
  const zones = [...state.zones];
  const zoneIds = ZONE_DEFS.map(def => {
    const existing = zones.find(zone => zone.nm === def.nm);
    if (existing) return existing.id;
    const retainedIds = new Set<number>();
    const keep = (id: unknown) => { if (typeof id === 'number' && Number.isSafeInteger(id) && id > 0) retainedIds.add(id); };
    zones.forEach(zone => keep(zone.id));
    state.tasks.forEach(task => {
      keep(task.zoneId);
      keep(task.questDeferral?.sourceZoneId);
      keep(task.questDeferral?.targetZoneId);
      keep(task.completionDeferral?.sourceZoneId);
      keep(task.completionDeferral?.targetZoneId);
    });
    state.taskHistory.forEach(event => keep(event.zoneId));
    Object.values(state.planDayZones || {}).forEach(blocks => blocks.forEach(block => keep(block.zoneId)));
    Object.values(state.planDayOff || {}).forEach(ids => ids.forEach(keep));
    state.dayTemplates.forEach(template => {
      (template.blocks || []).forEach(block => keep(block.zoneId));
      (template.tasks || []).forEach(item => keep(item.task?.zoneId));
    });
    keep(state.lastZoneId);
    let id = 1;
    while (retainedIds.has(id)) id++;
    zones.push({ id, nm: def.nm, color: def.color, slots: [{ s: def.s, e: def.e }], desc: def.desc, prio: 1, active: true, off: false, bound: true });
    return id;
  });
  const todayEntries = TODAY_TASKS.map(task => entry(task, zoneIds, today));
  const tomorrowEntries = TOMORROW_REPEATS.map(task => entry(task, zoneIds, tomorrow));
  const existingPresetKeys = new Set(state.tasks.map(task => task.dayPresetKey).filter((key): key is string => !!key));
  const first = appendDayPresetTasks(state.tasks, state.nid, PRESET_ID, todayEntries, today, zones);
  const second = appendDayPresetTasks(first.tasks, first.nid, PRESET_ID, tomorrowEntries, tomorrow, zones);
  const tasks = second.tasks.map(task => {
    if (!task.dayPresetKey?.startsWith(`${PRESET_ID}:`) || existingPresetKeys.has(task.dayPresetKey)) return task;
    const definition = [...TODAY_TASKS, ...TOMORROW_REPEATS].find(item => task.dayPresetKey === `${PRESET_ID}:${item.id}:${task.planDate}`);
    if (!definition) return task;
    return { ...task, ...definition.fields, repeat: definition.repeat || 'none' };
  });
  const folders = state.folders.some(folder => folder.id === FOLDER_ID)
    ? state.folders : [...state.folders, { id: FOLDER_ID, nm: demoPreset.folder.name, ico: demoPreset.folder.icon }];
  const noteSpecs = demoPreset.notes.map(({ text, time }) => ({
    txt: text.replace('{today}', dateLabel(today)).replace('{tomorrow}', dateLabel(tomorrow)), time, date: '',
  }));
  const qnotes = [...state.qnotes];
  let qnid = Math.max(state.qnid, ...qnotes.map(note => note.id + 1));
  for (const note of noteSpecs) {
    if (qnotes.some(existing => existing.folder === FOLDER_ID && existing.date === note.date && existing.txt === note.txt)) continue;
    qnotes.push({ id: qnid++, ...note, folder: FOLDER_ID });
  }
  const planDayZones = { ...state.planDayZones };
  for (const date of [today, tomorrow]) {
    const blocks = planDayZones[date] ? [...planDayZones[date]] : state.dayBlocks(date).map(block => ({ ...block }));
    for (const [index, def] of ZONE_DEFS.entries()) {
      if (!blocks.some(block => block.zoneId === zoneIds[index] && block.s === def.s && block.e === def.e)) {
        blocks.push({ zoneId: zoneIds[index], s: def.s, e: def.e });
      }
    }
    planDayZones[date] = blocks;
  }
  return { tasks, nid: second.nid, zones, folders, qnotes, qnid, planDayZones, dates: [today, tomorrow] as const };
}
