import { readFile } from 'node:fs/promises';
import { test, expect, type Page } from '@playwright/test';

const incompatible = JSON.stringify({ saveDate: 'Mon Oct 12 2026', tasks: [{ id: 9, title: 'Old habit', type: 'habit' }] });
const nativeBackup = JSON.stringify({ saveDate: 'Sun Oct 11 2026', tasks: [
  { id: 81, title: 'Old native task', type: 'simple', done: false, someday: false, repeat: 'none', tags: [], created: '2026-10-11T09:00:00.000Z' },
] });

async function createTaskWithNote(page: Page, title: string) {
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill(title);
  await page.getByText('Нотатка (необов’язково)', { exact: true }).click();
  await page.locator('#edit-body').getByLabel('Нотатка до задачі').fill('Деталі збережені після відновлення.');
  await page.locator('#edit-save-fab').click();
}

test('automatically archives incompatible browser data, then creates and exports a noted task', async ({ page }, testInfo) => {
  await page.addInitScript(raw => {
    if (localStorage.getItem('flow_v2') === null) localStorage.setItem('flow_v2', raw);
  }, incompatible);
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
  const archive = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2_recovery_archive')!));
  expect(archive.sources).toEqual([expect.objectContaining({ raw: incompatible })]);
  expect(new Date(archive.savedAt).getTime()).toBeGreaterThan(0);

  await createTaskWithNote(page, 'Task after automatic recovery');
  await expect(page.getByText('Task after automatic recovery', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText('Task after automatic recovery', { exact: true })).toBeVisible();
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks.filter((task: any) => task.title === 'Task after automatic recovery')).toHaveLength(1);
  expect(saved.tasks.find((task: any) => task.title === 'Task after automatic recovery').note).toBe('Деталі збережені після відновлення.');

  await page.getByRole('button', { name: /Налаштування/ }).click();
  const archiveSection = page.locator('#settings-page section').filter({ has: page.getByRole('heading', { name: 'Архів несумісних даних', exact: true }) });
  await expect(archiveSection).toBeVisible();
  await page.setViewportSize({ width: 320, height: 844 });
  await page.screenshot({ path: testInfo.outputPath('incompatible-archive-settings-320.png'), fullPage: true });
  const downloadPromise = page.waitForEvent('download');
  await archiveSection.getByRole('button', { name: /Експортувати архів/ }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('flow-recovery-archive.json');
  const exported = JSON.parse(await readFile(await download.path()!, 'utf8'));
  expect(exported.sources).toEqual([expect.objectContaining({ raw: incompatible })]);
});

test('automatically archives a valid old native backup without importing its tasks', async ({ page }) => {
  await page.addInitScript(raw => {
    (window as any).__nativeSnapshot = localStorage.getItem('__test_native_snapshot') || '';
    (window as any).__backup = raw;
    (window as any).__archiveCalls = [];
    (window as any).FlowBridge = {
      load: () => (window as any).__nativeSnapshot,
      hasBackup: () => true,
      readBackup: () => (window as any).__backup,
      archiveRecovery: (value: string) => { (window as any).__archiveCalls.push(value); return true; },
      saveWithEvents: (snapshot: string) => { (window as any).__nativeSnapshot = snapshot; localStorage.setItem('__test_native_snapshot', snapshot); return true; },
      save: (snapshot: string) => { (window as any).__nativeSnapshot = snapshot; localStorage.setItem('__test_native_snapshot', snapshot); },
      saveNotif: () => {}, writeBackup: () => {},
    };
  }, nativeBackup);
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
  expect(await page.evaluate(() => (window as any).__archiveCalls)).toHaveLength(1);
  const archived = JSON.parse(await page.evaluate(() => (window as any).__archiveCalls[0]));
  expect(archived.sources).toEqual([expect.objectContaining({ raw: nativeBackup })]);
  expect(await page.getByText('Old native task', { exact: true }).count()).toBe(0);

  await createTaskWithNote(page, 'Task after native archival');
  const saved = await page.evaluate(() => JSON.parse((window as any).__nativeSnapshot));
  expect(saved.tasks.filter((task: any) => task.title === 'Old native task')).toHaveLength(0);
  expect(saved.tasks.filter((task: any) => task.title === 'Task after native archival')).toHaveLength(1);
  await page.reload();
  await expect(page.getByText('Task after native archival', { exact: true })).toBeVisible();
  const afterReload = await page.evaluate(() => JSON.parse((window as any).__nativeSnapshot));
  expect(afterReload.tasks.filter((task: any) => task.title === 'Old native task')).toHaveLength(0);
  expect(afterReload.tasks.filter((task: any) => task.title === 'Task after native archival')).toHaveLength(1);
});

test('native archive failure retains originals and blocks saving with an accurate error', async ({ page }) => {
  await page.addInitScript(raw => {
    (window as any).__archiveCalls = [];
    (window as any).__backup = raw;
    (window as any).FlowBridge = {
      load: () => '',
      hasBackup: () => true,
      readBackup: () => (window as any).__backup,
      archiveRecovery: (value: string) => { (window as any).__archiveCalls.push(value); return false; },
      save: () => {}, saveNotif: () => {}, writeBackup: () => {},
    };
  }, nativeBackup);
  await page.goto('/');
  await expect(page.getByText(/Не вдалося зберегти стару копію окремо/)).toBeVisible();
  expect(await page.evaluate(() => (window as any).__archiveCalls)).toHaveLength(1);
  expect(await page.evaluate(() => (window as any).FlowBridge.readBackup())).toBe(nativeBackup);
  await page.getByRole('button', { name: 'OK', exact: true }).click();
  await createTaskWithNote(page, 'Cannot persist until archive works');
  await expect(page.locator('.editor-error')).toContainText('Не вдалося автоматично архівувати старі дані');
  await expect(page.locator('.editor-error')).not.toContainText('Звільни місце');
  expect(await page.evaluate(() => (window as any).FlowBridge.readBackup())).toBe(nativeBackup);
});
