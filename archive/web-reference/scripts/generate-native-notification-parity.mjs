import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const outputPath = path.join(root, 'android/app/src/test/resources/native-notification-parity.json');
process.env.TZ = 'Europe/Kyiv';
const fixedNow = Date.parse('2026-10-16T09:00:00.000Z');
globalThis.Date = class extends Date {
  constructor(...args) { super(...(args.length ? args : [fixedNow])); }
  static now() { return fixedNow; }
};

const bundled = await build({
  stdin: {
    contents: `
      import { useStore } from './src/state/store.ts';
      import { collectState, applyState } from './src/services/persistence.ts';
      import { buildForegroundNotifPayload } from './src/bridge/notifPayload.ts';
      import { createTaskRecord } from './src/utils/taskFactory.ts';
      export { useStore, collectState, applyState, buildForegroundNotifPayload, createTaskRecord };
    `,
    resolveDir: root,
    sourcefile: 'native-notification-parity-entry.ts',
  },
  bundle: true,
  write: false,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  plugins: [{
    name: 'native-notification-parity-task-undo-stub',
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
const { useStore, collectState, applyState, buildForegroundNotifPayload, createTaskRecord } = await import(moduleUrl);

function run(name, input) {
  useStore.setState(useStore.getInitialState(), true);
  if (!applyState(input, true)) throw new Error(`Could not load notification parity state: ${name}`);
  useStore.getState().recalculateZoneUsage();
  const state = JSON.parse(JSON.stringify(collectState()));
  delete state.saveDate;
  return {
    name,
    now: fixedNow,
    zone: 'Europe/Kyiv',
    state,
    expectedNotification: JSON.parse(JSON.stringify(buildForegroundNotifPayload())),
  };
}

const created = '2026-10-15T07:00:00.000Z';
const task = (id, title, type = 'simple', zoneId = null, zone = null) => createTaskRecord(id, title, type, zoneId, zone, '', created);
const work = { id: 20, nm: 'Notification work', color: '#4080c0', desc: 'Work time', prio: 2, slots: [{ s: '09:00', e: '15:00' }] };
const later = { id: 21, nm: 'Notification evening', color: '#8040c0', desc: '', prio: 1, slots: [{ s: '15:00', e: '22:00' }] };

const fixtures = [
  run('default-empty-tasks', { tasks: [] }),
  run('five-quests-priorities-journal-counter-and-advance-reminder', {
    zones: [work, later],
    priorities: [4, null, 2],
    currentFocus: 'One small step',
    energy: 3,
    tasks: [
      { ...task(1, 'Small first step'), note: 'Preserve this note in questTasks' },
      { ...task(2, 'Counter progress', 'counter'), counter: 6, counterTarget: 20, counterStep: 2, counterUnit: 'min', counterDay: '2026-10-16' },
      task(3, 'Journal event', 'negative'),
      task(4, 'Priority in this zone', 'simple', work.id, work),
      task(5, 'Another available task'),
      task(6, 'Fifth visible task'),
      task(7, 'Sixth available task'),
      task(8, 'Wait for evening', 'simple', later.id, later),
      { ...task(9, 'Completed today', 'simple', work.id, work), done: true, doneDate: '2026-10-16' },
      { ...task(10, 'Afternoon appointment', 'sched'), schedDate: '2026-10-16', schedTime: '12:10', remindBeforeMinutes: 30 },
    ],
  }),
  run('calendar-repeats-and-effective-planner-rule', {
    zones: [work, later],
    planRules: [{ id: 'notification-rule', start: '2026-10-16', end: '2026-10-18', days: [{ tplId: 'notification-template', rest: false }] }],
    dayTemplates: [{ id: 'notification-template', name: 'Notification override', blocks: [{ zoneId: later.id, s: '11:00', e: '13:00' }], tasks: [] }],
    tasks: [
      { ...task(11, 'Daily completed occurrence', 'sched'), repeat: 'daily', schedDate: '2026-10-15', schedTime: '12:30', done: true, doneDate: '2026-10-16', remindBeforeMinutes: 15 },
      { ...task(12, 'Weekly alarm', 'alarm'), repeat: 'weekly', planDate: '2026-10-09', alarmTime: '13:00' },
      { ...task(13, 'Custom weekday reminder', 'sched'), repeat: 'custom', repeatDays: [true, false, false, false, true, false, false], schedDate: '2026-10-15', schedTime: '11:00', firedSched: true, remindBeforeMinutes: 5 },
    ],
  }),
];

await mkdir(path.dirname(outputPath), { recursive: true });
await writeFile(outputPath, `${JSON.stringify({ fixtureVersion: 1, generatedAt: '2026-10-16T12:00:00+03:00', cases: fixtures }, null, 2)}\n`, 'utf8');
console.log(`Wrote ${fixtures.length} native notification cases to ${path.relative(root, outputPath)}`);
