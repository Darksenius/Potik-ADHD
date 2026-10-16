import type { DateKey, DayTemplateTask, Folder, PlanDayZoneOverride, QuickNote, Task, TaskType, Zone } from '../types';
import { appendDayPresetTasks } from '../utils/dayPresetTasks';
import { addDaysDs, fmtDate } from '../utils/date';

const PRESET_ID = 'potik-intro-v1';
const FOLDER_ID = 'potik-intro-day';
const ZONE_DEFS = [
  { key: 'morning', nm: 'Ранок · демо', color: '#e4aa35', s: '07:00', e: '12:00', desc: 'Приклад часової зони для ознайомлення.' },
  { key: 'afternoon', nm: 'Після обіду · демо', color: '#4386d9', s: '13:00', e: '18:00', desc: 'Друга зона; розклад задач і дати задаються окремо.' },
] as const;

type DemoTask = { id: string; title: string; type: TaskType; zone?: number; note: string; repeat?: Task['repeat']; fields?: Partial<Task> };
const TODAY_TASKS: DemoTask[] = [
  { id: 'errand', title: 'Забрати посилку', type: 'simple', note: 'Код отримання: 4821. Справа без обов’язкового часу.', repeat: 'none' },
  { id: 'call', title: 'Подзвонити майстру', type: 'sched', note: 'Часова справа: дата й час зберігаються окремо.', repeat: 'none', fields: { schedTime: '14:30', reminderEnabled: false } },
  { id: 'leaving', title: 'Перед виходом', type: 'check', zone: 0, note: 'Зона дає часовий контекст; відміть окремі кроки.', fields: { items: [{ text: 'Ключі', done: false }, { text: 'Документи', done: false }, { text: 'Навушники', done: false }] } },
  { id: 'pages', title: 'Переглянути три сторінки', type: 'counter', note: 'Лічильник обсягу з ціллю 3.', repeat: 'none', fields: { counterTarget: 3, counterEnabled: true, counterStep: 1, counterUnit: 'count' } },
  { id: 'filter', title: 'Замовити фільтр', type: 'note', note: 'Розмір 20×25 см. Перевірити сумісність перед замовленням.', repeat: 'none' },
  { id: 'light', title: 'Вимкнути зовнішнє світло', type: 'alarm', note: 'Приклад сигналу у заданий час; сигнал вимкнено, доки ти не ввімкнеш нагадування.', repeat: 'none', fields: { alarmTime: '22:00', reminderEnabled: false } },
  { id: 'air', title: 'Провітрити кімнату', type: 'timewin', zone: 0, note: 'Вікно часу обмежує показ задачі.', repeat: 'none', fields: { windowStart: '09:00', windowEnd: '11:00' } },
  { id: 'photos', title: 'Розібрати фото', type: 'ctx', zone: 1, note: 'Мітки контексту й зона — різні підказки.', repeat: 'none', fields: { repeatInterval: 2, repeatUnit: 'day', repeatMs: 172800000, ctxTags: ['вдома', '15 хв'] } },
  { id: 'impulse', title: 'Записати небажаний імпульс', type: 'negative', note: 'Журнал фіксує подію, це не дія для «Виконано». У цьому прикладі штраф досвіду вимкнено.', repeat: 'none', fields: { repeatDays: [true, false, true, false, true, false, false], negXp: 0 } },
  { id: 'sweep', title: 'Підмести підлогу під час входу', type: 'zonelinked', zone: 1, note: 'Справа для конкретної зони. Окремий приклад нижче демонструє повтор «Кожна зона».', repeat: 'none' },
];
const TOMORROW_REPEATS: DemoTask[] = [
  { id: 'r-daily', title: 'Повтор: щодня', type: 'simple', note: 'Приклад повторення «Щодня».', repeat: 'daily' },
  { id: 'r-weekly', title: 'Повтор: щотижня', type: 'check', note: 'Приклад повторення «Щотижня».', repeat: 'weekly' },
  { id: 'r-weekdays', title: 'Повтор: будні', type: 'counter', note: 'Приклад повторення «Будні».', repeat: 'weekdays', fields: { counterTarget: 5, counterEnabled: true } },
  { id: 'r-weekend', title: 'Повтор: вихідні', type: 'note', note: 'Приклад повторення «Вихідні».', repeat: 'weekend' },
  { id: 'r-interval', title: 'Повтор: раз на 2 дні', type: 'ctx', note: 'Приклад повторення за інтервалом.', repeat: 'interval', fields: { repeatInterval: 2, repeatUnit: 'day', repeatMs: 172800000, ctxTags: ['приклад'] } },
  { id: 'r-custom', title: 'Повтор: пн, ср, пт', type: 'timewin', note: 'Приклад вибраних днів тижня.', repeat: 'custom', fields: { repeatDays: [true, false, true, false, true, false, false], windowStart: '10:00', windowEnd: '12:00' } },
  { id: 'r-everyzone', title: 'Повтор: кожна зона', type: 'zonelinked', zone: 0, note: 'Приклад повторення при кожному вході у зону.', repeat: 'everyzone' },
  { id: 'r-none', title: 'Без повторення', type: 'alarm', note: 'Разова справа без повторення; сигнал вимкнено.', repeat: 'none', fields: { alarmTime: '18:00', reminderEnabled: false } },
];

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
    const definition = [...TODAY_TASKS, ...TOMORROW_REPEATS].find(item => task.dayPresetKey === `${PRESET_ID}:${item.id}:${task.planDate}:${task.planDate}`);
    if (!definition) return task;
    return { ...task, ...definition.fields, repeat: definition.repeat || 'none' };
  });
  const folders = state.folders.some(folder => folder.id === FOLDER_ID)
    ? state.folders : [...state.folders, { id: FOLDER_ID, nm: 'DEMO · День і нотатки', ico: '🧭' }];
  const noteSpecs = [
    { txt: `DEMO · Сьогодні, ${dateLabel(today)}: переглянь план і вибери один крок.`, time: '09:00', date: '' },
    { txt: `DEMO · Завтра, ${dateLabel(tomorrow)}: подзвонити майстру о 14:30.`, time: '14:30', date: '' },
    { txt: 'DEMO · Зона задає часовий контекст; планова дата — окреме поле.', time: '', date: '' },
  ];
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
