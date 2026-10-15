import { test, expect } from '@playwright/test';

test('failed inbox save keeps the text and retries into the same task', async ({ page }) => {
  await page.addInitScript(() => {
    (window as any).__failNextStateWrite = 0;
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key: string, value: string) {
      if (key === 'flow_v2' && (window as any).__failNextStateWrite > 0) {
        (window as any).__failNextStateWrite--;
        throw new DOMException('Storage is full', 'QuotaExceededError');
      }
      return original.call(this, key, value);
    };
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Вхідні', exact: true }).click();
  await page.evaluate(() => { (window as any).__failNextStateWrite = 1; });

  await page.getByLabel('Нова проста задача').fill('capture retry marker');
  await page.getByRole('button', { name: 'Додати', exact: true }).click();
  await expect(page.getByLabel('Нова проста задача')).toHaveValue('capture retry marker');

  await page.getByLabel('Нова проста задача').fill('edited capture retry marker');
  await page.getByRole('button', { name: 'Додати', exact: true }).click();
  await expect.poll(() => page.evaluate(() => {
    const state = JSON.parse(localStorage.getItem('flow_v2') || '{}');
    return state.tasks?.filter((task: any) => task.title.includes('capture retry marker')).length || 0;
  })).toBe(1);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.tasks.filter((task: any) => task.title.includes('capture retry marker'))).toMatchObject([
    { title: 'edited capture retry marker', type: 'simple' },
  ]);

  await page.reload();
  const reloaded = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(reloaded.tasks.filter((task: any) => task.title.includes('capture retry marker'))).toMatchObject([
    { title: 'edited capture retry marker', type: 'simple' },
  ]);
});

test('native backup and report export failures are surfaced to the user', async ({ page }) => {
  await page.addInitScript(() => {
    (window as any).__rejectedExports = [];
    (window as any).FlowBridge = {
      load: () => '',
      saveWithEvents: () => true,
      saveNotif: () => {},
      writeBackup: () => {},
      readBackup: () => '',
      hasBackup: () => false,
      appVersion: () => 'test',
      exportTxt: (filename: string, content: string) => {
        (window as any).__rejectedExports.push({ filename, content });
        return false;
      },
    };
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  await page.getByText('Дані та резервні копії', { exact: true }).click();

  await page.getByRole('button', { name: 'Експорт', exact: true }).click();
  await expect(page.getByText('Android не зміг зберегти файл експорту. Спробуй ще раз.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Звіт (.md)', exact: true }).click();
  const exports = await page.evaluate(() => (window as any).__rejectedExports);
  expect(exports).toHaveLength(2);
  expect(exports[0].filename).toMatch(/^flow-backup-.*\.json$/);
  expect(exports[1].filename).toMatch(/^flow-export-.*\.md$/);
});
