import { test, expect, type Page } from '@playwright/test';

async function openInbox(page: Page) {
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Вхідні', exact: true }).click();
}

test('queued shade captures preserve literal text, commit once, and stay distinct', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    const restored = sessionStorage.getItem('shade-command-test');
    const mock = (window as any).__commands = restored ? JSON.parse(restored) : {
      fail: true,
      persisted: JSON.stringify({ saveDate: new Date().toDateString(), tasks: [], qnotes: [], weekTplSeeded: true }),
      pending: [
        { id: 7, event: 'capture_task:  Inbox from shade  ', createdAt: Date.now() },
        { id: 8, event: 'capture_note:  Note from shade  ', createdAt: Date.now() },
        { id: 9, event: 'capture_task:... literal ellipsis', createdAt: Date.now() },
        { id: 10, event: 'capture_note:... literal ellipsis\nsecond line', createdAt: Date.now() },
        { id: 11, event: 'capture_task:Same title', createdAt: Date.now() },
        { id: 12, event: 'capture_task:Same title', createdAt: Date.now() },
      ], commits: [] as string[],
    };
    (window as any).FlowBridge = {
      load: () => mock.persisted,
      save: () => {}, saveNotif: () => {}, writeBackup: () => {}, readBackup: () => '', hasBackup: () => false,
      saveWithEvents: (json: string, ids: string) => {
        if (mock.fail) return false;
        const ack = JSON.parse(ids);
        if (ack.length) { mock.commits.push(json); mock.persisted = json; }
        mock.pending = mock.pending.filter((e: any) => !ack.includes(e.id));
        return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {}, stop: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.pending) }),
      drainEvents: async () => { throw new Error('Old destructive drain must not be called'); },
    } } };
  });

  await page.goto('/');
  await page.clock.runFor(2000);
  expect(await page.evaluate(() => (window as any).__commands.pending)).toHaveLength(6);
  const failedState = await page.evaluate(() => JSON.parse((window as any).FlowBridge.load()));
  expect(failedState.tasks).toHaveLength(0);
  expect(failedState.qnotes).toHaveLength(0);
  await openInbox(page);
  await expect(page.locator('#inbox-sec')).not.toContainText('Inbox from shade');
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('#qn-list')).not.toContainText('Note from shade');

  await page.evaluate(() => {
    (window as any).__commands.fail = false;
    window.__flowDrainEvents?.();
    window.__flowDrainEvents?.();
  });
  await openInbox(page);
  await expect(page.locator('#inbox-sec')).toContainText('Inbox from shade');
  await expect(page.locator('#inbox-sec')).toContainText('... literal ellipsis');
  await expect(page.locator('#inbox-sec')).toContainText('Same title');
  await expect(page.locator('#inbox-sec').getByText('Same title', { exact: true })).toHaveCount(2);
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('#qn-list')).toContainText('Note from shade');
  await expect(page.locator('#qn-list')).toContainText('... literal ellipsis\nsecond line');

  const result = await page.evaluate(() => (window as any).__commands);
  expect(result.pending).toEqual([]);
  expect(result.commits).toHaveLength(1);
  const saved = JSON.parse(result.commits[0]);
  const tasks = saved.tasks.filter((t: any) => ['Inbox from shade', '... literal ellipsis', 'Same title'].includes(t.title));
  expect(tasks).toHaveLength(4);
  expect(tasks.filter((t: any) => t.title === 'Same title')).toHaveLength(2);
  for (const task of tasks) {
    expect(task).not.toHaveProperty('planDate');
    expect(task).not.toHaveProperty('schedDate');
    expect(task).not.toHaveProperty('schedTime');
    expect(task).not.toHaveProperty('remindBeforeMinutes');
  }
  expect(saved.qnotes.filter((n: any) => n.txt === 'Note from shade' || n.txt === '... literal ellipsis\nsecond line'))
    .toHaveLength(2);
  expect(saved.qnotes.filter((n: any) => ['Note from shade', '... literal ellipsis\nsecond line'].includes(n.txt))
    .every((n: any) => n.folder === 'impulse')).toBe(true);
  expect(saved.xp).toBe((failedState.xp || 0) + 4 * 10 + 2);

  await page.evaluate(() => {
    window.__flowDrainEvents?.();
    sessionStorage.setItem('shade-command-test', JSON.stringify((window as any).__commands));
  });
  await page.reload();
  await page.clock.runFor(2000);
  const reloaded = await page.evaluate(() => JSON.parse((window as any).FlowBridge.load()));
  expect(reloaded.tasks.filter((t: any) => t.title === 'Same title')).toHaveLength(2);
  expect(reloaded.qnotes.filter((n: any) => n.txt === 'Note from shade' || n.txt === '... literal ellipsis\nsecond line'))
    .toHaveLength(2);
});

test('deleting note folders keeps stored notes and later captures in an available folder', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    const restored = sessionStorage.getItem('note-folder-delete-test');
    const mock = (window as any).__commands = {
      persisted: restored || JSON.stringify({
        saveDate: new Date().toDateString(), weekTplSeeded: true, tasks: [], qnid: 503,
        qnotes: [
          { id: 501, txt: 'У загальній', folder: 'general', date: '2026-10-08', time: '10:00' },
          { id: 502, txt: 'В імпульсах', folder: 'impulse', date: '2026-10-08', time: '10:01' },
        ],
      }),
      pending: [],
    };
    (window as any).FlowBridge = {
      load: () => mock.persisted,
      save: () => {}, saveNotif: () => {}, writeBackup: () => {}, readBackup: () => '', hasBackup: () => false,
      saveWithEvents: (json: string, ids: string) => {
        mock.persisted = json;
        sessionStorage.setItem('note-folder-delete-test', json);
        const ack = JSON.parse(ids);
        mock.pending = mock.pending.filter((event: any) => !ack.includes(event.id));
        return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {}, stop: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.pending) }),
      drainEvents: async () => { throw new Error('Old destructive drain must not be called'); },
    } } };
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('#qn-list')).toContainText('У загальній');
  await expect(page.locator('#qn-list')).toContainText('В імпульсах');

  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Папки нотаток', exact: true }).click();
  await page.getByRole('button', { name: 'Видалити папку Загальне', exact: true }).click();
  await page.getByRole('button', { name: 'Видалити папку Імпульс', exact: true }).click();

  await page.evaluate(() => {
    const mock = (window as any).__commands;
    mock.pending = [{ id: 1, event: 'capture_note: Після видалення папок', createdAt: Date.now() }];
    window.__flowDrainEvents?.();
  });
  await expect.poll(async () => page.evaluate(() => JSON.parse((window as any).__commands.persisted).qnotes))
    .toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 501, folder: 'thought' }),
      expect.objectContaining({ id: 502, folder: 'thought' }),
      expect.objectContaining({ txt: 'Після видалення папок', folder: 'thought' }),
    ]));

  await page.getByRole('button', { name: '← Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await page.getByRole('button', { name: /Думка/ }).click();
  await expect(page.locator('#qn-list .qn-row')).toHaveCount(3);
  await page.reload();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await page.getByRole('button', { name: /Думка/ }).click();
  await expect(page.locator('#qn-list .qn-row')).toHaveCount(3);
  expect(await page.evaluate(() => JSON.parse((window as any).__commands.persisted).qnotes.map((note: any) => note.folder)))
    .toEqual(['thought', 'thought', 'thought']);

  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Папки нотаток', exact: true }).click();
  const folderRows = page.locator('.notes-folder-manager-row');
  while (await folderRows.count()) {
    const remaining = await folderRows.count();
    await folderRows.nth(0).getByRole('button').click();
    await expect(folderRows).toHaveCount(remaining - 1);
  }
  await page.evaluate(() => {
    const mock = (window as any).__commands;
    mock.pending = [{ id: 2, event: 'capture_note: Без жодної папки', createdAt: Date.now() }];
    window.__flowDrainEvents?.();
  });
  await expect.poll(async () => page.evaluate(() => JSON.parse((window as any).__commands.persisted).qnotes))
    .toEqual(expect.arrayContaining([
      expect.objectContaining({ txt: 'Без жодної папки', folder: '' }),
    ]));
  expect(await page.evaluate(() => JSON.parse((window as any).__commands.persisted).qnotes.every((note: any) => note.folder === '')))
    .toBe(true);

  await page.getByRole('button', { name: '← Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('#qn-list .qn-row')).toHaveCount(4);
  await expect(page.locator('#qn-list')).toContainText('Без папки');
  await page.reload();
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await expect(page.locator('#qn-list .qn-row')).toHaveCount(4);
  await expect(page.locator('#qn-list')).toContainText('Без папки');
  expect(await page.evaluate(() => JSON.parse((window as any).__commands.persisted).qnotes.every((note: any) => note.folder === '')))
    .toBe(true);
});
