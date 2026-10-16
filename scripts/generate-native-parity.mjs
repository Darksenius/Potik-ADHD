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
  import { completedTaskOccurrences } from './src/utils/taskHistory.ts';
  import { fmtDate } from './src/utils/date.ts';
  import taskTypes from './src/content/task-types.json' with { type: 'json' };
  export { createTaskRecord, createNotesSlice, createGamificationSlice, createTasksSlice, completedTaskOccurrences, fmtDate, taskTypes };
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
const { createTaskRecord, createNotesSlice, createGamificationSlice, createTasksSlice, completedTaskOccurrences, fmtDate, taskTypes } = await import(moduleUrl);

const transientFields = new Set(['qnid', 'dailyInsightNotice', 'selectedInsightDay', 'xpPopup', 'levelUpFlash', 'alarmTaskIds']);
const fixedZone = 'Europe/Kyiv';
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
  state = { ...defaults, ...createGamificationSlice(set, get), ...createNotesSlice(set, get), ...createTasksSlice(set, get), ...initial };
  const maxNoteId = Math.max(99, ...(state.qnotes || []).map((note) => Number(note.id) || 0));
  state.qnid = Math.max(100, maxNoteId + 1);
  state.alarmTaskIds = state.alarmTaskIds || [];
  state.getZoneOccurrenceKey = (zoneId, date) => `${fmtDate(date)}:${zoneId}:09:00`;
  state.getActiveZones = () => [];
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

const fixture = {
  fixtureVersion: 1,
  generatedAt: '2026-10-16T12:00:00+03:00',
  zone: fixedZone,
  now: fixedNow,
  taskXp: Object.fromEntries(Object.entries(taskTypes).map(([type, definition]) => [type, definition.xp])),
  taskFactoryCases,
  notes,
  xpCases,
  energyCases,
  completionCases,
};

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify(fixture, null, 2)}\n`, 'utf8');
console.log(`Wrote ${taskFactoryCases.length} task cases, ${notes.length} note-command fixtures, ${xpCases.length} XP cases, ${energyCases.length} energy cases, and ${completionCases.length} completion fixtures to ${path.relative(root, outputPath)}`);
