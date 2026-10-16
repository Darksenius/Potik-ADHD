import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { releaseSaveGuard, collectState } from '../src/services/persistence';
import { configureBackupFolder, restoreFromBackupFolder } from '../src/services/backupFolder';

const globals = globalThis as any;
let order: string[];
let confirms: string[];
let confirmChoice: boolean;
type PickerRequest = { purpose: 'backup' | 'restore' };
let picker: (request: PickerRequest) => Promise<Record<string, unknown>>;
let archiveResult: boolean;
let backupError: string;
let backupReject: string;
let roomValue = '';
let backupWrites = 0;

const backupJson = () => JSON.stringify(collectState());

function installBridges() {
  const choose = async (request: PickerRequest) => picker(request);
  const replace = async () => {
    order.push('backup');
    backupWrites += 1;
    if (backupReject) throw new Error(backupReject);
    return { lastError: backupError };
  };
  const plugin = {
    chooseBackupFolder: choose,
    confirmBackupReplacement: replace,
    flushBackup: replace,
    writeBackup: replace,
    async readBackup() { return ''; },
    async flush() { return true; },
    async save() { return true; },
  };
  globals.window = {
    confirm: (message: string) => { confirms.push(message); return confirmChoice; },
    Capacitor: { Plugins: { FlowNotif: plugin } },
    FlowBridge: {
      chooseBackupFolder: choose,
      confirmBackupReplacement: replace,
      flushBackup: replace,
      writeBackup: replace,
      archiveRecovery: (raw: string) => {
        order.push('archive');
        expect(JSON.parse(raw).sources[0].raw).toBeTruthy();
        return archiveResult;
      },
      saveWithEvents: (raw: string) => {
        order.push('room');
        roomValue = raw;
        return true;
      },
      load: () => '',
      readBackup: () => '',
      save: () => true,
      saveNotif: () => {},
    },
  };
  globals.Capacitor = globals.window.Capacitor;
  globals.localStorage = {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
  };
}

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
  releaseSaveGuard();
  order = [];
  confirms = [];
  confirmChoice = true;
  archiveResult = true;
  backupError = '';
  backupReject = '';
  roomValue = '';
  backupWrites = 0;
  picker = async () => ({ cancelled: true });
  installBridges();
});

test.afterEach(() => {
  delete globals.window;
  delete globals.Capacitor;
  delete globals.localStorage;
  releaseSaveGuard();
});

test('backup folder picker cancellation does not ask for overwrite or write a backup', async () => {
  let purpose = '';
  picker = async (request) => { purpose = request.purpose; return { cancelled: true }; };

  expect(await configureBackupFolder()).toBeUndefined();
  expect(purpose).toBe('backup');
  expect(confirms).toHaveLength(0);
  expect(backupWrites).toBe(0);
});

test('new backup folder is selected and receives the current backup', async () => {
  let purpose = '';
  picker = async (request) => { purpose = request.purpose; return { cancelled: false, hasBackup: false }; };

  expect(await configureBackupFolder()).toContain('Папку резервних копій обрано');
  expect(purpose).toBe('backup');
  expect(confirms).toHaveLength(0);
  expect(backupWrites).toBe(1);
});

test('declining replacement leaves an existing folder backup untouched', async () => {
  picker = async () => ({ cancelled: false, hasBackup: true });
  confirmChoice = false;

  expect(await configureBackupFolder()).toContain('Копію не змінено');
  expect(confirms).toHaveLength(1);
  expect(backupWrites).toBe(0);
});

test('malformed folder backup is rejected without archiving or committing', async () => {
  picker = async () => ({ cancelled: false, raw: '{broken' });

  await expect(restoreFromBackupFolder()).rejects.toThrow('пошкоджена');
  expect(order).toEqual([]);
  expect(roomValue).toBe('');
});

test('canceling restore confirmation leaves archive and Room unchanged', async () => {
  picker = async () => ({ cancelled: false, raw: backupJson() });
  confirmChoice = false;

  expect(await restoreFromBackupFolder()).toBeUndefined();
  expect(confirms).toHaveLength(1);
  expect(order).toEqual([]);
  expect(roomValue).toBe('');
});

test('archive failure aborts restore before Room commit', async () => {
  picker = async () => ({ cancelled: false, raw: backupJson() });
  archiveResult = false;

  await expect(restoreFromBackupFolder()).rejects.toThrow('Не вдалося заархівувати');
  expect(order).toEqual(['archive']);
  expect(roomValue).toBe('');
});

test('successful restore archives the current workspace before Room commit and backup confirmation', async () => {
  picker = async () => ({ cancelled: false, raw: backupJson() });

  expect(await restoreFromBackupFolder()).toContain('Дані відновлено');
  expect(order.indexOf('archive')).toBeGreaterThanOrEqual(0);
  expect(order.indexOf('room')).toBeGreaterThan(order.indexOf('archive'));
  expect(order.indexOf('backup')).toBeGreaterThan(order.indexOf('room'));
  expect(JSON.parse(roomValue).formatVersion).toBe(2);
  expect(backupWrites).toBe(1);
});

test('backup write failure is reported after the restored Room state is committed', async () => {
  picker = async () => ({ cancelled: false, raw: backupJson() });
  backupError = 'Cloud folder is unavailable';

  const message = await restoreFromBackupFolder();
  expect(message).toContain('Дані відновлено в застосунку');
  expect(message).toContain('Cloud folder is unavailable');
  expect(order.indexOf('room')).toBeGreaterThan(order.indexOf('archive'));
  expect(roomValue).not.toBe('');
  expect(backupWrites).toBe(1);
});

test('restoring identical already committed data reconnects the folder without another archive or import', async () => {
  const raw = backupJson();
  picker = async () => ({ cancelled: false, raw });
  window.FlowBridge!.load = () => raw;
  expect(await restoreFromBackupFolder()).toContain('Ці дані вже є');
  expect(order).toEqual(['backup']);
});

test('rejected backup confirmation reports failure after the restored Room state is committed', async () => {
  picker = async () => ({ cancelled: false, raw: backupJson() });
  backupReject = 'backup confirmation rejected';

  const message = await restoreFromBackupFolder();
  expect(message).toContain('Дані відновлено в застосунку');
  expect(message).toContain('backup confirmation rejected');
  expect(order.indexOf('room')).toBeGreaterThan(order.indexOf('archive'));
  expect(order.indexOf('backup')).toBeGreaterThan(order.indexOf('room'));
  expect(roomValue).not.toBe('');
});
