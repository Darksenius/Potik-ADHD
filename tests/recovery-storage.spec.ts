import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { loadState, checkBackupRecovery, saveState, saveIsBlocked, releaseSaveGuard, continueWithCurrentState, getSaveErrorMessage } from '../src/services/persistence';
import { importState } from '../src/services/exportImport';

const original = JSON.stringify({ tasks: [{ id: 4, title: 'Original', type: 'habit' }] });
let values: Map<string, string>;
const globals = globalThis as any;
test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  releaseSaveGuard();
  values = new Map();
  globals.window = {};
  globals.localStorage = { getItem: (key: string) => values.get(key) || null, setItem: (key: string, value: string) => values.set(key, value) };
});
test.afterEach(() => { delete globals.window; delete globals.localStorage; releaseSaveGuard(); });

function startRecovery(bridge: Record<string, unknown>) {
  window.FlowBridge = { load: () => original, readBackup: () => '', ...bridge } as any;
  expect(loadState()).toBeNull();
  expect(checkBackupRecovery().kind).toBe('unreadable');
  useStore.getState().setBackupBanner('unreadable');
}

test('native recovery commits only after a durable archive and retains exact source bytes', () => {
  const order: string[] = [];
  let archive = '';
  let room = original;
  startRecovery({
    archiveRecovery: (json: string) => { archive = json; order.push('archive'); return true; },
    saveWithEvents: (json: string, ids: string) => { expect(ids).toBe('[]'); order.push('commit'); room = json; return true; },
    saveNotif: () => {}, writeBackup: () => {},
  });
  useStore.setState({ notepad: 'Current workspace' });
  expect(continueWithCurrentState()).toBe(true);
  expect(order).toEqual(['archive', 'commit']);
  expect(JSON.parse(archive).sources).toEqual([{ source: 'Android', raw: original }]);
  expect(JSON.parse(room).notepad).toBe('Current workspace');
  expect(values.get('flow_v2')).toBe(room);
  expect(saveIsBlocked()).toBe(false);
});

test('failed commit after archiving reinstates recovery protection and leaves sources unchanged', () => {
  let commits = 0;
  startRecovery({ archiveRecovery: () => true, saveWithEvents: () => { commits++; return false; } });
  expect(continueWithCurrentState()).toBe(false);
  expect(commits).toBe(1);
  expect(saveIsBlocked()).toBe(true);
  expect(useStore.getState().backupBanner).toBe('unreadable');
  expect(values.has('flow_v2')).toBe(false);
  expect(saveState()).toBe(false);
  expect(commits).toBe(1);
});

test('failed backup restore reinstates the original recovery guard and workspace', () => {
  startRecovery({ saveWithEvents: () => false });
  useStore.setState({ notepad: 'Unchanged workspace' });
  expect(() => importState({ notepad: 'Imported workspace' }, 'restore')).toThrow('Не вдалося зберегти імпорт');
  expect(useStore.getState().notepad).toBe('Unchanged workspace');
  expect(saveIsBlocked()).toBe(true);
  expect(useStore.getState().backupBanner).toBe('unreadable');
  expect(values.has('flow_v2')).toBe(false);
});

test('storage advice is shown only when Android reports storage_full, not for a generic failure', () => {
  let code = 'database_error';
  window.FlowBridge = { saveWithEvents: () => false, getLastSaveError: () => code } as any;
  expect(saveState()).toBe(false);
  expect(getSaveErrorMessage()).toContain('Android не зміг записати');
  expect(getSaveErrorMessage()).not.toContain('Звільни місце');
  code = 'storage_full';
  expect(saveState()).toBe(false);
  expect(getSaveErrorMessage()).toContain('Звільни місце');
});
