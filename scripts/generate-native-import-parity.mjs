import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = path.join(root, 'android/app/src/test/resources/native-import-parity.json');
process.env.TZ = 'Europe/Kyiv';

const fixedNow = Date.parse('2026-10-16T09:00:00.000Z');
const FixedDate = class extends Date {
  constructor(...args) { super(...(args.length ? args : [fixedNow])); }
  static now() { return fixedNow; }
};
globalThis.Date = FixedDate;

const entry = `
  import { useStore } from './src/state/store.ts';
  import { collectState, applyState } from './src/services/persistence.ts';
  import { mergeState } from './src/services/exportImport.ts';
  import { createTaskRecord } from './src/utils/taskFactory.ts';
  import taskTypes from './src/content/task-types.json' with { type: 'json' };
  export { useStore, collectState, applyState, mergeState, createTaskRecord, taskTypes };
`;
const bundled = await build({
  stdin: { contents: entry, resolveDir: root, sourcefile: 'native-import-parity-entry.ts' },
  bundle: true,
  write: false,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  plugins: [{
    name: 'native-import-parity-task-undo-stub',
    setup(build) {
      build.onResolve({ filter: /\/services\/taskUndo$/ }, () => ({ path: 'taskUndo', namespace: 'fixture' }));
      build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({
        contents: 'export const captureTaskUndo = () => undefined; export const offerTaskUndo = () => {}; export const applyNativeTaskUndo = () => false; export const clearTaskUndo = () => {};',
        loader: 'js',
      }));
    },
  }],
});
const moduleUrl = `data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString('base64')}`;
const { useStore, collectState, applyState, mergeState, createTaskRecord, taskTypes } = await import(moduleUrl);

function persisted(state = collectState()) {
  const copy = JSON.parse(JSON.stringify(state));
  delete copy.saveDate;
  return copy;
}

function reset(initial) {
  useStore.setState(useStore.getInitialState(), true);
  if (!applyState(initial, true)) throw new Error('Could not load parity initial state');
  return persisted();
}

function run(name, mode, currentState, backup) {
  const initialState = reset(currentState);
  if (mode === 'restore') {
    if (!applyState(backup, true)) throw new Error(`TS restore failed for ${name}`);
  } else {
    mergeState(backup);
  }
  useStore.getState().recalculateZoneUsage();
  return {
    name,
    mode,
    zone: 'Europe/Kyiv',
    now: fixedNow,
    command: {
      kind: mode === 'restore' ? 'RestoreSnapshot' : 'ImportSnapshot',
      commandId: `import-parity-${name}`,
      activityAt: fixedNow,
      payload: backup,
    },
    initialState,
    expectedState: persisted(),
  };
}

const task = (id, title, created) => createTaskRecord(id, title, 'simple', null, null, '', created);
const currentTask = task(500, 'Keep this task', '2026-10-15T09:00:00.000Z');
const currentTaskForTemplate = task(510, 'Keep template neighbor', '2026-10-15T10:00:00.000Z');
const incomingZoneTask = {
  ...task(44, 'Incoming zoned task', '2026-10-15T11:00:00.000Z'),
  zoneId: 40,
  repeat: 'everyzone',
  zoneOccurrenceKey: '2026-10-16:40:09:00',
  occurrenceId: '44@2026-10-15T11:00:00.000Z|zone:2026-10-16:40:09:00',
};
const incomingOccurrence = incomingZoneTask.occurrenceId;

const fixtures = [
  run('restore-minimal-resets-current-state', 'restore', {
    tasks: [currentTask], nid: 501, theme: 'light', preferences: { themeMode: 'light', reduceMotion: true, showGamification: false },
  }, {
    formatVersion: 2, tasks: [task(3, 'Imported task', '2026-10-16T08:00:00.000Z')], saveDate: 'Fri Oct 16 2026',
  }),
  run('restore-legacy-routine-uses-save-date', 'restore', { tasks: [currentTask], nid: 501 }, {
    formatVersion: 2,
    recur: [{ id: 'r-old', nm: 'Read', unit: 'check', val: 0, done: true }],
    saveDate: 'Thu Oct 15 2026',
  }),
  run('restore-legacy-planner-task-and-event', 'restore', { tasks: [currentTask], nid: 501 }, {
    formatVersion: 2,
    planItems: { '2026-10-16': [
      { title: 'Migrated scheduled task', time: '10:30', done: true },
      { title: 'Calendar event', time: '12:00', isEvent: true },
    ] },
    saveDate: 'Fri Oct 16 2026',
  }),
  run('merge-identical-task-content-with-different-id', 'merge', {
    tasks: [currentTask], nid: 501,
  }, {
    formatVersion: 2,
    tasks: [{ ...currentTask, id: 999 }],
  }),
  run('merge-remaps-zone-history-and-occurrence-identities', 'merge', {
    zones: [{ id: 4, nm: 'Office parity zone', color: '#4f83cc', desc: '', prio: 1, slots: [{ s: '09:00', e: '17:00' }] }],
  }, {
    formatVersion: 2,
    zones: [{ id: 40, nm: 'Office parity zone', color: '#4f83cc', desc: '', prio: 1, slots: [{ s: '09:00', e: '17:00' }] }],
    tasks: [incomingZoneTask],
    taskHistory: [{
      id: `${incomingOccurrence}#0:1792141200000:completed`, occurrenceId: incomingOccurrence,
      taskId: 44, taskCreated: incomingZoneTask.created, title: incomingZoneTask.title, type: 'simple',
      kind: 'completed', sequence: 0, at: 1792141200000, date: '2026-10-16', time: '12:00', zoneId: 40,
    }],
  }),
  run('merge-same-name-day-template-keeps-current-task-and-adds-entry', 'merge', {
    tasks: [currentTaskForTemplate], nid: 511,
    dayTemplates: [{ id: 'current-source-id', name: 'Parity morning', blocks: [], tasks: [] }],
  }, {
    formatVersion: 2,
    dayTemplates: [{ id: 'different-backup-source-id', name: 'Parity morning', blocks: [], tasks: [
      { id: 'backup-entry', task: { title: 'Template entry task', type: 'simple', zoneId: null } },
    ] }],
  }),
];

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify({ fixtureVersion: 1, generatedAt: '2026-10-16T12:00:00+03:00', taskXp: Object.fromEntries(Object.entries(taskTypes).map(([key, value]) => [key, value.xp])), cases: fixtures }, null, 2)}\n`, 'utf8');
console.log(`Wrote ${fixtures.length} native import/restore cases to ${path.relative(root, outputPath)}`);
