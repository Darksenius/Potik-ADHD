import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { loadState, releaseSaveGuard, saveState } from '../src/services/persistence';

let values: Map<string, string>;
const globals = globalThis as unknown as { window?: unknown; localStorage?: unknown };

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  releaseSaveGuard();
  values = new Map();
  globals.window = {};
  globals.localStorage = {
    getItem: (key: string) => values.get(key) || null,
    setItem: (key: string, value: string) => values.set(key, value),
  };
});

test.afterEach(() => {
  delete globals.window;
  delete globals.localStorage;
});

function installBridge(overrides: Record<string, unknown>) {
  const calls: string[] = [];
  (window as unknown as { FlowBridge: unknown }).FlowBridge = {
    save: () => { calls.push('legacy-save'); },
    saveNotif: () => { calls.push('notification'); },
    writeBackup: () => { calls.push('backup'); },
    ...overrides,
  };
  return calls;
}

test('failed native commit reports unsaved changes and does not create a misleading newer browser mirror', () => {
  const before = JSON.stringify({ tasks: [], notepad: 'Надійно збережено', saveDate: 'Thu Oct 08 2026' });
  values.set('flow_v2', before);
  const calls = installBridge({ load: () => before, saveWithEvents: () => false });
  useStore.setState({ notepad: 'Новий важливий запис' });
  expect(saveState()).toBe(false);
  expect(values.get('flow_v2')).toBe(before);
  expect(calls).toEqual([]);
  expect(useStore.getState().notepad).toBe('Новий важливий запис');
  expect(useStore.getState().toastMessage).toContain('Зміни ще не збережено');
  loadState();
  expect(useStore.getState().notepad).toBe('Надійно збережено');
});

test('a throwing legacy native save cannot report success through localStorage', () => {
  values.set('flow_v2', 'previous mirror');
  const calls = installBridge({ save: () => { throw new Error('Database full'); } });
  expect(saveState()).toBe(false);
  expect(values.get('flow_v2')).toBe('previous mirror');
  expect(calls).toEqual([]);
  expect(useStore.getState().toastMessage).toContain('Не вдалося зберегти');
});

test('ordinary save commits with no acknowledgement ids and succeeds when browser quota is exhausted', () => {
  const commits: { json: string; ids: number[] }[] = [];
  const calls = installBridge({ saveWithEvents: (json: string, ids: string) => {
    commits.push({ json, ids: JSON.parse(ids) });
    return true;
  } });
  globals.localStorage = { setItem: () => { throw new Error('QuotaExceededError'); } };
  useStore.setState({ notepad: 'Збережено у Room' });
  expect(saveState()).toBe(true);
  expect(commits).toHaveLength(1);
  expect(commits[0].ids).toEqual([]);
  expect(JSON.parse(commits[0].json).notepad).toBe('Збережено у Room');
  expect(calls).toEqual(['notification', 'backup']);
});

test('event acknowledgement failure leaves mirror and queue intact until retry succeeds', () => {
  let fail = true;
  let pending = [7, 8];
  const commits: string[] = [];
  values.set('flow_v2', 'previous mirror');
  installBridge({ saveWithEvents: (json: string, ids: string) => {
    if (fail) return false;
    const acknowledged = JSON.parse(ids) as number[];
    pending = pending.filter(id => !acknowledged.includes(id));
    commits.push(json);
    return true;
  } });
  expect(saveState([7])).toBe(false);
  expect(pending).toEqual([7, 8]);
  expect(values.get('flow_v2')).toBe('previous mirror');
  fail = false;
  expect(saveState([7])).toBe(true);
  expect(pending).toEqual([8]);
  expect(commits).toHaveLength(1);
  expect(values.get('flow_v2')).toBe(commits[0]);
});

test('successful retry refreshes both stores; distinct valid startup copies retain Room authority', () => {
  let room = JSON.stringify({ tasks: [], notepad: 'Room version', saveDate: 'Thu Oct 08 2026' });
  values.set('flow_v2', JSON.stringify({ tasks: [], notepad: 'Other valid mirror' }));
  let fail = true;
  installBridge({ load: () => room, saveWithEvents: (json: string) => {
    if (fail) return false;
    room = json;
    return true;
  } });
  loadState();
  expect(useStore.getState().notepad).toBe('Room version');
  useStore.setState({ notepad: 'Retry this edit' });
  expect(saveState()).toBe(false);
  fail = false;
  expect(saveState()).toBe(true);
  expect(values.get('flow_v2')).toBe(room);
  useStore.setState({ notepad: '' });
  loadState();
  expect(useStore.getState().notepad).toBe('Retry this edit');
});

test('web-only saving remains available without an Android bridge', () => {
  useStore.setState({ notepad: 'Browser edit' });
  expect(saveState()).toBe(true);
  expect(JSON.parse(values.get('flow_v2')!).notepad).toBe('Browser edit');
});
