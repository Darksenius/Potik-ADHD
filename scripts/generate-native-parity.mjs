import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = path.join(root, 'android/app/src/test/resources/native-parity.json');
process.env.TZ = 'Europe/Kyiv';

const fixedNow = Date.parse('2026-10-16T09:00:00.000Z'); // 12:00 in Europe/Kyiv.
const FixedDate = class extends Date {
  constructor(...args) { super(...(args.length ? args : [fixedNow])); }
  static now() { return fixedNow; }
};
globalThis.Date = FixedDate;

const entry = `
  import { createTaskRecord } from './src/utils/taskFactory.ts';
  import { createNotesSlice } from './src/state/slices/notesSlice.ts';
  import { createGamificationSlice } from './src/state/slices/gamificationSlice.ts';
  import { createTasksSlice } from './src/state/slices/tasksSlice.ts';
  import { createLifecycleSlice } from './src/state/slices/lifecycleSlice.ts';
  import { completedTaskOccurrences } from './src/utils/taskHistory.ts';
  import { fmtDate } from './src/utils/date.ts';
  import { dayBlocksFor, dayIsRestFor } from './src/state/slices/plannerHelpers.ts';
  import { buildQuestTimeline, createQuestDeferral, getAssignedZoneOccurrences, getQuestDayRanges, getQuestTaskAvailability, selectQuestTasks } from './src/utils/questPolicy.ts';
  import { isTaskInInbox, isTaskInTimeWindow, nextTaskReminderAt, repeatDueOn, taskDoneOn, taskPreReminderAt } from './src/utils/taskSchedule.ts';
  import taskTypes from './src/content/task-types.json' with { type: 'json' };
  export { createTaskRecord, createNotesSlice, createGamificationSlice, createTasksSlice, createLifecycleSlice, completedTaskOccurrences, dayBlocksFor, dayIsRestFor, buildQuestTimeline, createQuestDeferral, getAssignedZoneOccurrences, getQuestDayRanges, getQuestTaskAvailability, selectQuestTasks, fmtDate, isTaskInInbox, isTaskInTimeWindow, nextTaskReminderAt, repeatDueOn, taskDoneOn, taskPreReminderAt, taskTypes };
`;
const bundled = await build({
  stdin: { contents: entry, resolveDir: root, sourcefile: 'native-parity-entry.ts' },
  bundle: true,
  write: false,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  plugins: [{
    name: 'native-parity-task-undo-stub',
    setup(build) {
      build.onResolve({ filter: /\/services\/taskUndo$/ }, () => ({ path: 'taskUndo', namespace: 'fixture' }));
      build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({
        contents: 'export const captureTaskUndo = () => undefined; export const offerTaskUndo = () => {};',
        loader: 'js',
      }));
    },
  }],
});
const moduleUrl = `data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString('base64')}`;
const { createTaskRecord, createNotesSlice, createGamificationSlice, createTasksSlice, createLifecycleSlice, completedTaskOccurrences, dayBlocksFor, dayIsRestFor, buildQuestTimeline, createQuestDeferral, getAssignedZoneOccurrences, getQuestDayRanges, getQuestTaskAvailability, selectQuestTasks, fmtDate, isTaskInInbox, isTaskInTimeWindow, nextTaskReminderAt, repeatDueOn, taskDoneOn, taskPreReminderAt, taskTypes } = await import(moduleUrl);

const transientFields = new Set(['qnid', 'dailyInsightNotice', 'selectedInsightDay', 'xpPopup', 'levelUpFlash', 'alarmTaskIds']);
const fixedZone = 'Europe/Kyiv';
const activeZoneId = 7;
const iso = (at) => new Date(at).toISOString();
const dayAt = (day, hour = 9) => Date.parse(`${day}T${String(hour).padStart(2, '0')}:00:00.000Z`);

function capture(state) {
  const persisted = Object.fromEntries(Object.entries(state).filter(([key, value]) =>
    typeof value !== 'function' && !transientFields.has(key)));
  return JSON.parse(JSON.stringify(persisted));
}

function createStore(initial = {}) {
  let state;
  const get = () => state;
  const set = (update) => {
    const patch = typeof update === 'function' ? update(state) : update;
    if (patch !== state) state = { ...state, ...patch };
  };
  const defaults = {
    formatVersion: 2,
    tasks: [], taskHistory: [], recur: [], zones: [], nid: 300,
    planItems: {}, planRestDays: {}, planSchedules: {}, planDayZones: {}, planDayOff: {},
    planRules: [], planDayLog: {}, dayTemplates: [], weekTemplates: [], weekTplSeeded: false,
    priorities: [null, null, null], focusChips: [], focusLog: [], currentFocus: '',
    taskEditorDraft: '', theme: 'dark', preferences: { themeMode: 'system', reduceMotion: false, showGamification: true },
  };
  state = { ...defaults, ...createGamificationSlice(set, get), ...createNotesSlice(set, get), ...createTasksSlice(set, get), ...createLifecycleSlice(set, get), ...initial };
  const maxNoteId = Math.max(99, ...(state.qnotes || []).map((note) => Number(note.id) || 0));
  state.qnid = Math.max(100, maxNoteId + 1);
  state.alarmTaskIds = state.alarmTaskIds || [];
  state.getZoneOccurrenceKey = (zoneId, date) => `${fmtDate(date)}:${zoneId}:09:00`;
  state.getActiveZones = () => {
    const active = get().zones.find((candidate) => candidate.id === activeZoneId);
    return active ? [active] : [];
  };
  state.showToast = () => {};
  state.dayStats = (day) => ({
    tasksDone: completedTaskOccurrences(get().taskHistory, get().tasks)
      .filter((event) => event.date === day).length,
  });
  return { get, state: () => state };
}

function perform(store, command) {
  const s = store.get();
  const payload = command.payload || {};
  switch (command.kind) {
    case 'AddQuickNote': return s.addQuickNote(payload.txt, payload.folder, command.activityAt);
    case 'UpdateQuickNote': return s.updateQuickNote(command.noteId, payload.txt, payload.folder);
    case 'DeleteFolder': return s.deleteFolder(command.folderId);
    case 'SaveNotepadAsNote': return s.saveNotepadAsNote(command.folderId);
    case 'SetNotepad': return s.setNotepad(payload.notepad);
    case 'SetNotepadFolder': return s.setNotepadFolder(command.folderId);
    default: throw new Error(`Unsupported fixture command ${command.kind}`);
  }
}

function performCompletion(store, command) {
  if (command.kind !== 'SetTaskDone') throw new Error(`Unsupported completion fixture command ${command.kind}`);
  return store.get().setTaskDone(command.taskId, command.done, command.completedAt);
}

function runDeferralFixture(name, initialData, taskId, kind, requestedAt, timezone = fixedZone) {
  process.env.TZ = timezone;
  const input = capture(createStore(initialData).state());
  const store = createStore(input);
  const api = store.get();
  api.dayBlocks = (day) => dayBlocksFor(day, api.planDayZones || {}, api.planRules || [], api.dayTemplates || []);
  api.getZoneOccurrenceKey = (zoneId, date) => {
    const day = fmtDate(date);
    const minute = date.getHours() * 60 + date.getMinutes();
    const range = getAssignedZoneOccurrences({ zones: api.zones, planDayOff: api.planDayOff, dayBlocks: api.dayBlocks }, day)
      .find((slot) => slot.zoneId === zoneId && minute >= slot.startMinute && minute < slot.endMinute);
    return range ? `${day}:${zoneId}:${String(Math.floor(range.startMinute / 60)).padStart(2, '0')}:${String(range.startMinute % 60).padStart(2, '0')}` : undefined;
  };
  api.deferTask(taskId, kind, requestedAt);
  const result = {
    name, initialState: input,
    command: { kind, taskId, requestedAt, activityAt: requestedAt, now: fixedNow, zone: timezone },
    expectedState: capture(store.state()),
  };
  process.env.TZ = fixedZoneEnvironment;
  return result;
}

function performEditor(store, command) {
  if (command.kind === 'CreateTask') return store.get().saveTask(null, command.payload);
  if (command.kind === 'UpdateTask') return store.get().saveTask(command.taskId, command.payload);
  throw new Error(`Unsupported editor fixture command ${command.kind}`);
}

function performLifecycle(store, command) {
  const api = store.get();
  const date = new Date(command.at);
  if (command.kind === 'ResetRepeating') return api.resetRepeatingTasksFor(date);
  if (command.kind === 'CheckZoneRepeats') return api.checkZoneRepeats(date, command.taskId);
  if (command.kind === 'DailyReset') return api.dailyResetIfNeeded(command.savedDate);
  throw new Error(`Unsupported lifecycle fixture command ${command.kind}`);
}

function runFixture(scenario, initialData, commands) {
  let input = capture(createStore(initialData).state());
  return commands.map((command) => {
    const store = createStore(input);
    perform(store, command);
    const expectedState = capture(store.state());
    const fixture = {
      scenario,
      initialState: input,
      command: {
        kind: command.kind,
        payload: command.payload || {},
        id: command.id,
        ...(command.noteId === undefined ? {} : { noteId: command.noteId }),
        ...(command.folderId === undefined ? {} : { folderId: command.folderId }),
        activityAt: command.activityAt,
        now: command.now ?? fixedNow,
        zone: fixedZone,
      },
      expectedState,
    };
    input = expectedState;
    return fixture;
  });
}

function runCompletionFixture(scenario, initialData, commands) {
  let input = capture(createStore(initialData).state());
  return commands.map((command) => {
    const store = createStore(input);
    performCompletion(store, command);
    const expectedState = capture(store.state());
    const fixture = {
      scenario,
      initialState: input,
      command: {
        kind: command.kind,
        taskId: command.taskId,
        done: command.done,
        completedAt: command.completedAt,
        now: fixedNow,
        zone: fixedZone,
      },
      expectedState,
    };
    input = expectedState;
    return fixture;
  });
}

function runStateCommandFixture(scenario, initialData, command, performCommand) {
  const input = capture(createStore(initialData).state());
  const store = createStore(input);
  performCommand(store, command);
  return {
    scenario,
    initialState: input,
    command: {
      ...command,
      now: fixedNow,
      zone: fixedZone,
      activeZoneId,
    },
    expectedState: capture(store.state()),
  };
}

function runCaptureFixture(scenario, initialData, title, requestedId, activityAt) {
  const input = capture(createStore(initialData).state());
  const store = createStore(input);
  store.get().addQuickTaskFromShade(title, requestedId, activityAt);
  return {
    scenario, initialState: input, title, requestedId, activityAt,
    now: fixedNow, zone: fixedZone,
    expectedState: capture(store.state()),
  };
}

function taskState(task) {
  return {
    ...initial(), tasks: [task], taskHistory: [], nid: task.id + 1,
    priorities: [task.id, null, null], alarmTaskIds: [task.id], planDayLog: {},
  };
}

const zone = { id: 7, nm: 'Домашня', color: '#4f83cc', desc: '', prio: 0, slots: [{ s: '06:00', e: '24:00' }] };
const taskFactoryCases = Object.keys(taskTypes).map((type, index) => {
  const input = {
    id: 700 + index,
    title: `Parity ${type}`,
    type,
    zoneId: zone.id,
    zone,
    alarmTime: type === 'alarm' ? '07:45' : '',
    created: iso(fixedNow),
  };
  return { type, input, expectedTask: JSON.parse(JSON.stringify(createTaskRecord(
    input.id, input.title, input.type, input.zoneId, input.zone, input.alarmTime, input.created,
  ))) };
});

const initial = (overrides = {}) => ({
  formatVersion: 2, tasks: [], zones: [], qnotes: [], notepad: '', notepadFolder: 'thought',
  progressionVersion: 2, progressDays: [], dailyInsightClaims: [], xp: 0, xpTotal: 0, level: 1,
  done: 0, unlocked: [], ...overrides,
});
const atToday = fixedNow;
const atYesterday = dayAt('2026-10-15', 9);
const todayLabel = new Date(fixedNow).toDateString();
const captureCases = [
  runCaptureFixture('notification-capture-honors-requested-id-and-awards-10-xp', initial({ nid: 300 }), 'Захоплено зі сповіщення', 777, atToday),
  runCaptureFixture('duplicate-requested-id-is-a-no-op-without-second-xp', initial({
    tasks: [createTaskRecord(777, 'Початкова задача', 'simple', null, null, '', iso(atYesterday))],
    nid: 778, xp: 10, xpTotal: 10, dayXpGained: 10, xpDay: todayLabel,
  }), 'Повторний захват', 777, atToday),
  runCaptureFixture('delayed-yesterday-capture-keeps-today-xp-day-and-archives-activity-date', initial({
    nid: 300, progressDays: ['2026-10-14'], xpDay: todayLabel,
  }), 'Вчорашнє сповіщення', 888, atYesterday),
];
const editorZone = { id: 7, nm: 'Робота', color: '#7ed321', slots: [{ s: '09:00', e: '18:00' }], desc: '', prio: 1 };

const editorInputs = [
  { type: 'simple', payload: { title: 'Проста справа', type: 'simple', zoneId: null } },
  { type: 'sched', payload: { title: 'Точний дзвінок', type: 'sched', zoneId: 7, schedDate: '2026-10-17', schedTime: '15:30', reminderEnabled: true, remindBeforeMinutes: 10 } },
  { type: 'check', payload: { title: 'Перед виходом', type: 'check', items: [{ text: 'Документи' }, { text: 'Ключі' }] } },
  { type: 'counter', payload: { title: 'Прочитані сторінки', type: 'counter', counterStep: 2, counterTarget: 12, counterUnit: 'pages', counterEnabled: true } },
  { type: 'note', payload: { title: 'З деталями', type: 'note', note: 'Рядок перший\nРядок другий' } },
  { type: 'alarm', payload: { title: 'Нагадування', type: 'alarm', zoneId: 7, alarmTime: '09:05', reminderEnabled: true } },
  { type: 'timewin', payload: { title: 'Нічне вікно', type: 'timewin', zoneId: 7, windowStart: '22:00', windowEnd: '02:00' } },
  { type: 'ctx', payload: { title: 'Позначене', type: 'ctx', tags: ['вдома', 'тихо'] } },
  { type: 'negative', payload: { title: 'Журнал', type: 'negative', negXp: 0 } },
  { type: 'zonelinked', payload: { title: 'Справа для зони', type: 'zonelinked', zoneId: null, repeat: 'everyzone' } },
];
const editorCases = editorInputs.map(({ type, payload }) => runStateCommandFixture(
  `create-${type}`, initial({ zones: [editorZone], nid: 900 }),
  { kind: 'CreateTask', payload, id: `editor-create-${type}`, activityAt: atToday }, performEditor,
));

const schedToSimple = {
  ...createTaskRecord(910, 'Запланована', 'sched', 7, editorZone, '', iso(atYesterday)),
  schedDate: '2026-10-17', schedTime: '15:30', planDate: '2026-10-17', reminderEnabled: true,
  remindBeforeMinutes: 10, firedSched: true, firedPre: true,
};
const counterToNote = {
  ...createTaskRecord(911, 'Лічильник', 'counter', null, null, '', iso(atYesterday)),
  counter: 7, counterCreditedProgress: 5, counterStep: 2, counterTarget: 8, counterUnit: 'pages', counterEnabled: true,
};
const noteWithUnknowns = {
  ...createTaskRecord(912, 'Нотатка', 'note', null, null, '', iso(atYesterday)),
  note: 'Початковий рядок', customTaskData: { keep: true, nested: ['a', 2] },
};
const checkWithProgress = {
  ...createTaskRecord(913, 'Чекліст', 'check', null, null, '', iso(atYesterday)),
  items: [{ text: 'Готово', done: true, credited: true }, { text: 'Ще ні', done: false, credited: false }],
};
const editorUpdates = [
  runStateCommandFixture('update-sched-to-simple', initial({ zones: [editorZone], tasks: [schedToSimple], nid: 914 }), {
    kind: 'UpdateTask', taskId: 910, payload: { title: 'Звичайна справа', type: 'simple', zoneId: null, tags: [] }, id: 'editor-update-sched-simple', activityAt: atToday,
  }, performEditor),
  runStateCommandFixture('update-counter-to-note-with-attached-counter', initial({ tasks: [counterToNote], nid: 914 }), {
    kind: 'UpdateTask', taskId: 911, payload: { title: 'Нотатка з лічильником', type: 'note', note: 'Зберегти поступ', counterEnabled: true, counterTarget: 9, counterStep: 1, counterUnit: 'pages', tags: [] }, id: 'editor-update-counter-note', activityAt: atToday,
  }, performEditor),
  runStateCommandFixture('update-note-preserves-unknown-task-and-root-fields', initial({
    tasks: [noteWithUnknowns], nid: 914, customRootData: { revision: 4, nested: { preserve: 'yes' } },
  }), {
    kind: 'UpdateTask', taskId: 912, payload: { title: 'Нотатка оновлена', type: 'note', note: 'Оновлена\nбагаторядкова примітка', tags: [] }, id: 'editor-update-note-unknowns', activityAt: atToday,
  }, performEditor),
  runStateCommandFixture('update-check-keeps-existing-steps-and-progress', initial({ tasks: [checkWithProgress], nid: 914 }), {
    kind: 'UpdateTask', taskId: 913, payload: { title: 'Чекліст оновлено', type: 'check', items: [{ text: 'Замінити не можна' }], tags: [] }, id: 'editor-update-check-keeps-steps', activityAt: atToday,
  }, performEditor),
];

const yesterdayString = new Date(atYesterday).toDateString();
const lifecycleCases = [
  runStateCommandFixture('daily-unfinished-counter-progress-resets', initial({ tasks: [{
    ...createTaskRecord(920, 'Щоденний лічильник', 'counter', null, null, '', iso(atYesterday)),
    repeat: 'daily', counter: 3, counterCreditedProgress: 3, cntHit: true, counterDay: '2026-10-15',
  }] }), { kind: 'ResetRepeating', at: atToday }, performLifecycle),
  runStateCommandFixture('weekdays-repeat-unchanged-on-weekend', initial({ tasks: [{
    ...createTaskRecord(921, 'Будні', 'counter', null, null, '', iso(atYesterday)),
    repeat: 'weekdays', counter: 4, counterDay: '2026-10-16',
  }] }), { kind: 'ResetRepeating', at: dayAt('2026-10-17') }, performLifecycle),
  runStateCommandFixture('interval-due-resets-future-stays-done', initial({ tasks: [
    { ...createTaskRecord(922, 'Інтервал настав', 'simple', null, null, '', iso(atYesterday)), repeat: 'interval', repeatMs: 3600000, done: true, nextRepeatAt: atToday - 1, counterDay: '2026-10-15' },
    { ...createTaskRecord(923, 'Інтервал попереду', 'simple', null, null, '', iso(atYesterday)), repeat: 'interval', repeatMs: 3600000, done: true, nextRepeatAt: atToday + 3600000, counterDay: '2026-10-15' },
  ] }), { kind: 'ResetRepeating', at: atToday }, performLifecycle),
  runStateCommandFixture('everyzone-first-observation-only-sets-key', initial({ tasks: [{
    ...createTaskRecord(924, 'Перша зона', 'zonelinked', 7, editorZone, '', iso(atYesterday)),
    repeat: 'everyzone', counter: 3, zoneOccurrenceKey: undefined,
  }] }), { kind: 'CheckZoneRepeats', at: atToday, taskId: 924 }, performLifecycle),
  runStateCommandFixture('everyzone-new-slot-resets-occurrence', initial({ tasks: [{
    ...createTaskRecord(925, 'Нова зона', 'zonelinked', 7, editorZone, '', iso(atYesterday)),
    repeat: 'everyzone', done: true, counter: 3, zoneOccurrenceKey: '2026-10-16:7:06:00',
  }] }), { kind: 'CheckZoneRepeats', at: atToday, taskId: 925 }, performLifecycle),
  runStateCommandFixture('midnight-preserves-past-preset-negative-log', initial({ tasks: [{
    ...createTaskRecord(926, 'Збережений журнал', 'negative', null, null, '', iso(atYesterday)),
    dayPresetKey: 'preset:2026-10-15', planDate: '2026-10-15', repeat: 'none', counter: 4, counterDay: '2026-10-15', counterCreditedProgress: 4,
  }] }), { kind: 'DailyReset', at: atToday, savedDate: yesterdayString }, performLifecycle),
];

function scheduleFixture(scenario, method, task, { at = fixedNow, day, history = [], timeline = [] } = {}) {
  let value;
  switch (method) {
    case 'taskDoneOn': value = taskDoneOn(task, day, history); break;
    case 'isTaskInInbox': value = isTaskInInbox(task); break;
    case 'isTaskInTimeWindow': value = isTaskInTimeWindow(task, new Date(at)); break;
    case 'nextTaskReminderAt': value = nextTaskReminderAt(task, new Date(at), timeline); break;
    case 'repeatDueOn': value = repeatDueOn(task, new Date(at)); break;
    case 'taskPreReminderAt': value = taskPreReminderAt(task, at); break;
    default: throw new Error(`Unsupported schedule fixture method ${method}`);
  }
  return {
    scenario, method, task, ...(day === undefined ? {} : { day }), at,
    zone: fixedZone, history, timeline,
    expectedValue: value === undefined ? null : value,
  };
}

const scheduleCases = [
  scheduleFixture('once-task-done-today-is-not-done-yesterday-with-empty-history', 'taskDoneOn', {
    id: 950, title: 'Сьогодні виконано', type: 'simple', done: true, doneDate: '2026-10-16',
    created: iso(atYesterday), repeat: 'none',
  }, { day: '2026-10-15', history: [] }),
  scheduleFixture('zero-zone-id-remains-unscheduled-inbox-task', 'isTaskInInbox', {
    id: 951, title: 'У вхідних', type: 'simple', done: false, trashed: false, someday: false,
    zoneId: 0, created: iso(atYesterday), repeat: 'none',
  }),
  scheduleFixture('fired-one-shot-alarm-has-no-next-reminder', 'nextTaskReminderAt', {
    id: 952, title: 'Вже спрацював', type: 'alarm', done: false, alarmTime: '09:05', alarmFired: true,
    created: iso(atYesterday), repeat: 'none',
  }),
  scheduleFixture('legacy-single-digit-hour-window-at-0930', 'isTaskInTimeWindow', {
    id: 953, title: 'Вікно', type: 'timewin', windowStart: '9:00', windowEnd: '10:00',
  }, { at: Date.parse('2026-10-16T06:30:00.000Z') }),
  scheduleFixture('weekly-repeat-matches-valid-monday-anchor', 'repeatDueOn', {
    id: 954, title: 'Щотижня', type: 'simple', repeat: 'weekly', planDate: '2026-10-12',
    created: iso(atYesterday),
  }, { at: dayAt('2026-10-19') }),
  scheduleFixture('overnight-window-includes-0100', 'isTaskInTimeWindow', {
    id: 955, title: 'Нічне вікно', type: 'timewin', windowStart: '22:00', windowEnd: '02:00',
  }, { at: Date.parse('2026-10-15T22:00:00.000Z') }),
  scheduleFixture('scheduled-reminder-uses-exact-local-date-and-time', 'nextTaskReminderAt', {
    id: 956, title: 'Заплановано', type: 'sched', schedDate: '2026-10-17', planDate: '2026-10-17',
    schedTime: '15:30', reminderEnabled: true, remindBeforeMinutes: 10, repeat: 'none',
  }),
  scheduleFixture('scheduled-pre-reminder-is-ten-minutes-before-due', 'taskPreReminderAt', {
    id: 957, title: 'Попереднє нагадування', type: 'sched', schedDate: '2026-10-17', schedTime: '15:30',
    reminderEnabled: true, remindBeforeMinutes: 10,
  }, { at: Date.parse('2026-10-17T12:30:00.000Z') }),
];

function plannerCase(name, state, day) {
  return {
    name, state, day,
    blocks: dayBlocksFor(day, state.planDayZones || {}, state.planRules || [], state.dayTemplates || []),
    rest: dayIsRestFor(day, state.planRestDays || {}, state.planRules || []),
  };
}

const plannerCases = [
  plannerCase('manual-empty-overrides-template', {
    planDayZones: { '2026-10-16': [] }, planRestDays: {},
    planRules: [{ start: '2026-10-16', end: '2026-10-16', days: [{ tplId: 'work' }] }],
    dayTemplates: [{ id: 'work', name: 'Робота', blocks: [{ zoneId: 7, s: '09:00', e: '10:00' }] }],
  }, '2026-10-16'),
  plannerCase('explicit-false-rest-overrides-rule', {
    planDayZones: {}, planRestDays: { '2026-10-16': false },
    planRules: [{ start: '2026-10-16', end: '2026-10-16', days: [{ rest: true }] }], dayTemplates: [],
  }, '2026-10-16'),
  plannerCase('last-overlapping-rule-wins', {
    planDayZones: {}, planRestDays: {},
    planRules: [
      { start: '2026-10-16', end: '2026-10-20', days: [{ tplId: 'first' }] },
      { start: '2026-10-16', end: '2026-10-18', days: [{ tplId: 'last' }] },
    ],
    dayTemplates: [
      { id: 'first', name: 'Перше', blocks: [{ zoneId: 1, s: '06:00', e: '07:00' }] },
      { id: 'last', name: 'Останнє', blocks: [{ zoneId: 2, s: '07:00', e: '08:00' }] },
    ],
  }, '2026-10-16'),
  plannerCase('seven-day-cycle-selects-monday-after-dst', {
    planDayZones: {}, planRestDays: {},
    planRules: [{ start: '2026-10-01', end: '2026-11-01', days: [
      { tplId: 'mon' }, { tplId: 'tue' }, { tplId: 'wed' }, { tplId: 'thu' }, { tplId: 'fri' }, { tplId: 'sat' }, { tplId: 'sun' },
    ] }],
    dayTemplates: [{ id: 'mon', name: 'Понеділок', blocks: [{ zoneId: 3, s: '09:00', e: '10:00' }] }],
  }, '2026-10-26'),
  plannerCase('three-day-cycle-keeps-date-index-across-dst', {
    planDayZones: {}, planRestDays: {},
    planRules: [{ start: '2026-10-24', end: '2026-10-31', days: [
      { tplId: 'first' }, { tplId: 'second' }, { tplId: 'third' },
    ] }],
    dayTemplates: [{ id: 'third', name: 'Третій день', blocks: [{ zoneId: 4, s: '14:00', e: '15:00' }] }],
  }, '2026-10-26'),
  plannerCase('deleted-template-produces-no-blocks', {
    planDayZones: {}, planRestDays: {},
    planRules: [{ start: '2026-10-16', end: '2026-10-16', days: [{ tplId: 'deleted', rest: true }] }], dayTemplates: [],
  }, '2026-10-16'),
  plannerCase('template-blocks-strip-extra-metadata', {
    planDayZones: {}, planRestDays: {},
    planRules: [{ start: '2026-10-16', end: '2026-10-16', days: [{ tplId: 'meta' }] }],
    dayTemplates: [{ id: 'meta', name: 'Метадані', blocks: [{ zoneId: 7, s: '09:00', e: '10:00', id: 'block-1', ov: true, custom: 'drop' }] }],
  }, '2026-10-16'),
];

const xpInputs = [
  {
    name: 'daily-cap-240-plus-20',
    initialState: initial({ xp: 40, xpTotal: 240, dayXpGained: 240, xpDay: todayLabel }),
    amount: 20, activityAt: atToday,
  },
  {
    name: 'penalty-after-cap-keeps-positive-room-used',
    initialState: initial({ xp: 20, xpTotal: 300, dayXpGained: 250, xpDay: todayLabel }),
    amount: -5, activityAt: atToday,
  },
  {
    name: 'level-boundary-95-plus-10',
    initialState: initial({ xp: 95, xpTotal: 95, level: 1, dayXpGained: 0, xpDay: todayLabel }),
    amount: 10, activityAt: atToday,
  },
  {
    name: 'late-yesterday-reward-archives-without-today-claim',
    initialState: initial({ progressDays: ['2026-10-14'], xpDay: todayLabel }),
    amount: 5, activityAt: atYesterday,
  },
  {
    name: 'negative-xp-floors-balance-without-lowering-lifetime-total',
    initialState: initial({ xp: 3, xpTotal: 125, level: 1, dayXpGained: 40, xpDay: todayLabel }),
    amount: -10, activityAt: atToday,
  },
  {
    name: 'zero-award-does-not-record-activity',
    initialState: initial({ progressionVersion: 0, xpDay: '', progressDays: [] }),
    amount: 0, activityAt: atToday,
  },
];

const xpCases = xpInputs.map(({ name, initialState, amount, activityAt }) => {
  const store = createStore(initialState);
  const persistedInitial = capture(store.state());
  store.get().award(amount, activityAt);
  return {
    name,
    initialState: persistedInitial,
    amount,
    activityAt,
    now: fixedNow,
    zone: fixedZone,
    expectedState: capture(store.state()),
  };
});

let energyInput = capture(createStore(initial()).state());
const energyCases = [2, 4].map((value, index) => {
  const store = createStore(energyInput);
  store.get().setEnergy(value);
  const expectedState = capture(store.state());
  const result = {
    name: index === 0 ? 'first-energy-change-awards-xp' : 'energy-change-within-hour-cooldown',
    initialState: energyInput,
    value,
    now: fixedNow,
    zone: fixedZone,
    expectedState,
  };
  energyInput = expectedState;
  return result;
});

const simpleTask = createTaskRecord(801, 'Проста задача', 'simple', null, null, '', iso(atToday - 86400000));
const intervalTask = {
  ...createTaskRecord(802, 'Інтервальна задача', 'simple', null, null, '', iso(atToday - 86400000)),
  repeat: 'interval', repeatMs: 3600000, repeatInterval: 60, repeatUnit: 'min',
};
const zoneTask = {
  ...createTaskRecord(803, 'Зонова задача', 'zonelinked', 7, { id: 7, nm: 'Робота', color: '#4f83cc' }, '', iso(atToday - 86400000)),
  repeat: 'everyzone', zoneOccurrenceKey: undefined,
};
const completionCases = [
  ...runCompletionFixture('simple-done-reopen-redo-does-not-recredit', taskState(simpleTask), [
    { kind: 'SetTaskDone', taskId: 801, done: true, completedAt: atToday },
    { kind: 'SetTaskDone', taskId: 801, done: false, completedAt: atToday + 60_000 },
    { kind: 'SetTaskDone', taskId: 801, done: true, completedAt: atToday + 120_000 },
  ]),
  ...runCompletionFixture('interval-completion-sets-next-repeat', taskState(intervalTask), [
    { kind: 'SetTaskDone', taskId: 802, done: true, completedAt: atToday },
  ]),
  ...runCompletionFixture('every-zone-completion-uses-resolved-slot-key', taskState(zoneTask), [
    { kind: 'SetTaskDone', taskId: 803, done: true, completedAt: atToday },
  ]),
];

const notes = [
  ...runFixture('first-note-then-same-day-edit', initial(), [
    { kind: 'AddQuickNote', id: 'note-add-1', activityAt: atToday, payload: { txt: 'Перший запис', folder: 'impulse' } },
    { kind: 'UpdateQuickNote', id: 'note-edit-1', noteId: 100, activityAt: atToday + 60_000, payload: { txt: 'Перший запис змінено', folder: 'thought' } },
  ]),
  ...runFixture('capture-without-folders', initial({ folders: [] }), [
    { kind: 'AddQuickNote', id: 'note-no-folders', activityAt: atToday, payload: { txt: 'Без папки', folder: 'impulse' } },
  ]),
  ...runFixture('notepad-save-clears-atomically', initial({
    notepad: '  Думка з блокнота  ', notepadFolder: 'thought',
  }), [
    { kind: 'SaveNotepadAsNote', id: 'notepad-save', activityAt: atToday, folderId: null },
  ]),
  ...runFixture('delete-folder-rebinds-notes-tasks-and-notepad', initial({
    folders: [
      { id: 'general', nm: 'Загальне', ico: '📝' },
      { id: 'work', nm: 'Робота', ico: '💼' },
    ],
    qnotes: [{ id: 105, txt: 'Робоча думка', folder: 'work', date: '2026-10-15', time: '12:00', extra: { keep: true } }],
    tasks: [{ id: 300, title: 'Задача', type: 'simple', done: false, someday: false, created: iso(atToday), folderId: 'work', extra: { keep: true } }],
    notepadFolder: 'work',
  }), [
    { kind: 'DeleteFolder', id: 'folder-delete', activityAt: atToday, folderId: 'work' },
  ]),
  ...runFixture('activity-after-skipped-day', initial({ progressDays: ['2026-10-14'] }), [
    { kind: 'AddQuickNote', id: 'note-after-gap', activityAt: atToday, payload: { txt: 'Після пропуску' } },
  ]),
  ...runFixture('late-yesterday-activity', initial({ progressDays: ['2026-10-14'] }), [
    { kind: 'AddQuickNote', id: 'late-note', activityAt: atYesterday, now: fixedNow, payload: { txt: 'Вчорашня нотатка' } },
  ]),
];

const questZoneCasesInput = [
  {
    name: 'planned-ranges-override-baseline-and-tie-by-order', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: {
      zones: [
        { id: 1, nm: 'Перша', prio: 2, slots: [{ s: '09:00', e: '12:00' }] },
        { id: 2, nm: 'Друга', prio: 5, slots: [{ s: '10:00', e: '11:00' }] },
      ], planDayOff: {}, planDayZones: { '2026-10-16': [{ zoneId: 1, s: '10:00', e: '11:00' }] }, planRules: [], dayTemplates: [],
    },
  },
  {
    name: 'planned-zones-override-inactive-bound-and-dayoff-baselines', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: {
      zones: [
        { id: 3, nm: 'Неактивна', active: false, prio: 1, slots: [{ s: '08:00', e: '10:00' }] },
        { id: 4, nm: 'Прихована', bound: true, prio: 2, slots: [{ s: '10:00', e: '12:00' }] },
        { id: 5, nm: 'Вихідний', prio: 3, slots: [{ s: '12:00', e: '14:00' }] },
      ], planDayOff: { '2026-10-16': [5] }, planDayZones: {},
      planRules: [{ start: '2026-10-16', end: '2026-10-16', days: [{ tplId: 'plans' }] }],
      dayTemplates: [{ id: 'plans', name: 'План', blocks: [
        { zoneId: 3, s: '08:30', e: '09:30' }, { zoneId: 4, s: '10:30', e: '11:30' }, { zoneId: 5, s: '12:30', e: '13:30' },
      ] }],
    },
  },
  {
    name: 'off-zones-have-no-baseline-or-planned-ranges', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: { zones: [{ id: 6, nm: 'Вимкнена', off: true, prio: 1, slots: [{ s: '09:00', e: '12:00' }] }], planDayOff: {}, planDayZones: { '2026-10-16': [{ zoneId: 6, s: '10:00', e: '11:00' }] }, planRules: [], dayTemplates: [] },
  },
  {
    name: 'touching-assigned-ranges-merge-within-zone', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: { zones: [{ id: 8, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '09:00' }, { s: '09:00', e: '10:00' }, { s: '09:30', e: '11:00' }] }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] },
  },
  {
    name: 'assigned-overlay-retains-each-zone-range', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: { zones: [{ id: 9, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '12:00' }] }, { id: 10, nm: 'Робота', prio: 4, slots: [{ s: '09:00', e: '11:00' }] }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] },
  },
  {
    name: 'zone-id-above-signed-int-range', day: '2026-10-16', now: Date.parse('2026-10-16T06:00:00.000Z'), zone: fixedZone, days: 1,
    state: { zones: [{ id: 4294967297, nm: 'Великий ID', prio: 1, slots: [{ s: '09:00', e: '10:00' }] }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] },
  },
  {
    name: 'new-york-spring-forward-is-23-hours', day: '2026-03-08', now: Date.parse('2026-03-08T12:00:00.000Z'), zone: 'America/New_York', days: 1,
    state: { zones: [{ id: 11, nm: 'Ранок', prio: 1, slots: [{ s: '01:00', e: '04:00' }] }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] },
  },
  {
    name: 'new-york-fall-back-is-25-hours', day: '2026-11-01', now: Date.parse('2026-11-01T12:00:00.000Z'), zone: 'America/New_York', days: 1,
    state: { zones: [{ id: 12, nm: 'Ніч', prio: 1, slots: [{ s: '00:30', e: '02:30' }] }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] },
  },
];

const fixedZoneEnvironment = process.env.TZ;
const zoneCases = questZoneCasesInput.map(({ name, state, now, zone: timezone, days, day }) => {
  process.env.TZ = timezone;
  const input = {
    zones: state.zones,
    planDayOff: state.planDayOff,
    now: new Date(now),
    days,
    dayBlocks: (date) => dayBlocksFor(date, state.planDayZones || {}, state.planRules || [], state.dayTemplates || []),
  };
  const result = {
    name, state, now, zone: timezone, days, day,
    expectedTimeline: buildQuestTimeline(input),
    expectedRanges: getQuestDayRanges(input, day),
    expectedAssigned: getAssignedZoneOccurrences(input, day),
  };
  process.env.TZ = fixedZoneEnvironment;
  return result;
});
process.env.TZ = fixedZone;

const questCaseInputs = [
  {
    name: 'priority-cannot-bypass-wrong-zone', zone: fixedZone, now: Date.parse('2026-10-16T06:30:00.000Z'), days: 1,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '10:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '10:00', e: '12:00' }] }], priorities: [101], tasks: [{ id: 101, title: 'Робоча справа', type: 'simple', done: false, someday: false, zoneId: 2, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 101,
  },
  {
    name: 'next-zone-expiry-returns-to-source-zone', zone: fixedZone, now: Date.parse('2026-10-16T07:30:00.000Z'), requestedAt: Date.parse('2026-10-16T05:30:00.000Z'), days: 1,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '09:00' }, { s: '10:00', e: '12:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '09:00', e: '10:00' }] }], priorities: [], tasks: [{ id: 102, title: 'Вільна справа', type: 'simple', done: false, someday: false, zoneId: null, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 102, kind: 'next-zone',
  },
  {
    name: 'last-zone-next-deferral-falls-back-to-not-today', zone: fixedZone, now: Date.parse('2026-10-16T18:30:00.000Z'), days: 1,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '18:00', e: '22:00' }] }], priorities: [], tasks: [{ id: 103, title: 'Вільна справа', type: 'simple', done: false, someday: false, zoneId: null, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 103, kind: 'next-zone',
  },
  {
    name: 'next-zone-skips-free-gap-for-next-named-zone', zone: fixedZone, now: Date.parse('2026-10-16T06:30:00.000Z'), days: 1,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '10:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '11:00', e: '12:00' }] }], priorities: [], tasks: [{ id: 107, title: 'Домашня справа', type: 'simple', done: false, someday: false, zoneId: 1, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 107, kind: 'next-zone',
  },
  {
    name: 'linked-not-today-starts-tomorrow-in-own-zone', zone: fixedZone, now: Date.parse('2026-10-16T08:30:00.000Z'), days: 2,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '10:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '10:00', e: '12:00' }] }], priorities: [], tasks: [{ id: 104, title: 'Робоча справа', type: 'simple', done: false, someday: false, zoneId: 2, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 104, kind: 'not-today',
  },
  {
    name: 'unbound-not-today-uses-23-hours-across-spring-dst', zone: 'America/New_York', now: Date.parse('2026-03-07T17:00:00.000Z'), days: 2,
    state: { zones: [], priorities: [], tasks: [{ id: 105, title: 'Вільна справа', type: 'simple', done: false, someday: false, zoneId: null, repeat: 'none', created: '2026-03-06T15:00:00.000Z' }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 105, kind: 'not-today',
  },
  {
    name: 'next-zone-deferral-keeps-linked-task-original-zone', zone: fixedZone, now: Date.parse('2026-10-16T06:30:00.000Z'), days: 1,
    state: { zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '09:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '09:00', e: '10:00' }] }], priorities: [106], tasks: [{ id: 106, title: 'Домашня справа', type: 'simple', done: false, someday: false, zoneId: 1, repeat: 'none', created: '2026-10-15T09:00:00.000Z', questDeferral: { kind: 'next-zone', requestedAt: Date.parse('2026-10-16T06:00:00.000Z'), sourceZoneId: 0, targetZoneId: 2, startAt: Date.parse('2026-10-16T06:00:00.000Z'), endAt: Date.parse('2026-10-16T07:00:00.000Z') } }], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [] }, taskId: 106,
  },
];

const questCases = questCaseInputs.map(({ name, state, now, requestedAt, zone: timezone, days, taskId, kind }) => {
  process.env.TZ = timezone;
  const input = {
    zones: state.zones,
    planDayOff: state.planDayOff,
    now: new Date(now),
    days,
    dayBlocks: (day) => dayBlocksFor(day, state.planDayZones || {}, state.planRules || [], state.dayTemplates || []),
  };
  const timeline = buildQuestTimeline(input);
  let task = state.tasks.find((candidate) => candidate.id === taskId);
  const context = { now: new Date(now), timeline, zones: state.zones, priorities: state.priorities };
  const expectedDeferral = kind
    ? createQuestDeferral(task, kind, { timeline, zones: state.zones, requestedAt: requestedAt ?? now })
    : undefined;
  if (requestedAt !== undefined) task = { ...task, questDeferral: expectedDeferral };
  const tasks = state.tasks.map((candidate) => candidate.id === taskId ? task : candidate);
  const result = {
    name, state: { ...state, tasks }, now, ...(requestedAt === undefined ? {} : { requestedAt }), zone: timezone, timeline, task,
    expectedAvailability: getQuestTaskAvailability(task, context),
    expectedSelected: selectQuestTasks(tasks, context),
  };
  if (kind) {
    result.kind = kind;
    result.expectedDeferral = expectedDeferral;
  }
  process.env.TZ = fixedZoneEnvironment;
  return result;
});
process.env.TZ = fixedZone;

const deferCommandCases = [
  runDeferralFixture('defer-linked-task-to-next-zone-persists-zone-occurrence', {
    zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '10:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '10:00', e: '12:00' }] }],
    tasks: [{ id: 111, title: 'Домашня справа', type: 'zonelinked', done: false, someday: false, zoneId: 1, repeat: 'everyzone', created: '2026-10-15T09:00:00.000Z' }],
    priorities: [111], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [],
  }, 111, 'next-zone', Date.parse('2026-10-16T05:30:00.000Z')),
  runDeferralFixture('defer-linked-task-until-tomorrow-own-zone', {
    zones: [{ id: 1, nm: 'Дім', prio: 1, slots: [{ s: '08:00', e: '10:00' }] }, { id: 2, nm: 'Робота', prio: 2, slots: [{ s: '10:00', e: '12:00' }] }],
    tasks: [{ id: 112, title: 'Робоча справа', type: 'simple', done: false, someday: false, zoneId: 2, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }],
    priorities: [112], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [],
  }, 112, 'not-today', Date.parse('2026-10-16T05:30:00.000Z')),
  runDeferralFixture('defer-unbound-task-across-spring-dst', {
    zones: [],
    tasks: [{ id: 113, title: 'Вільна справа', type: 'simple', done: false, someday: false, zoneId: null, repeat: 'none', created: '2026-03-06T15:00:00.000Z' }],
    priorities: [113], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [],
  }, 113, 'not-today', Date.parse('2026-03-07T17:00:00.000Z'), 'America/New_York'),
  runDeferralFixture('defer-from-last-named-zone-past-free-time', {
    zones: [{ id: 3, nm: 'Вечірня зона', prio: 1, slots: [{ s: '18:00', e: '22:00' }] }],
    tasks: [{ id: 114, title: 'Вільна справа', type: 'simple', done: false, someday: false, zoneId: null, repeat: 'none', created: '2026-10-15T09:00:00.000Z' }],
    priorities: [114], planDayOff: {}, planDayZones: {}, planRules: [], dayTemplates: [],
  }, 114, 'next-zone', Date.parse('2026-10-16T18:30:00.000Z')),
];
process.env.TZ = fixedZone;

const fixture = {
  fixtureVersion: 1,
  generatedAt: '2026-10-16T12:00:00+03:00',
  zone: fixedZone,
  now: fixedNow,
  taskXp: Object.fromEntries(Object.entries(taskTypes).map(([type, definition]) => [type, definition.xp])),
  taskFactoryCases,
  activeZoneId,
  editorCases: [...editorCases, ...editorUpdates],
  lifecycleCases,
  scheduleCases,
  plannerCases,
  captureCases,
  notes,
  xpCases,
  energyCases,
  completionCases,
  zoneCases,
  questCases,
  deferCommandCases,
};

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8');
console.log(`Wrote ${taskFactoryCases.length} task cases, ${editorCases.length + editorUpdates.length} editor cases, ${lifecycleCases.length} lifecycle cases, ${scheduleCases.length} schedule cases, ${plannerCases.length} planner cases, ${captureCases.length} capture cases, ${notes.length} note-command fixtures, ${xpCases.length} XP cases, ${energyCases.length} energy cases, ${completionCases.length} completion fixtures, ${zoneCases.length} zone cases, ${questCases.length} quest cases, and ${deferCommandCases.length} defer-command cases to ${path.relative(root, outputPath)}`);
