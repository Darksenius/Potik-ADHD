import { test, expect } from '@playwright/test';

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
  await page.getByRole('button', { name: '↓ Вхідні', exact: true }).click();
  await expect(page.locator('#inbox-sec')).not.toContainText('Inbox from shade');
  await page.getByRole('button', { name: '⋯ Ще', exact: true }).click();
  await page.getByRole('button', { name: '📝 Блокнот', exact: true }).click();
  await expect(page.locator('#qn-list')).not.toContainText('Note from shade');

  await page.evaluate(() => {
    (window as any).__commands.fail = false;
    window.__flowDrainEvents?.();
    window.__flowDrainEvents?.();
  });
  await page.getByRole('button', { name: '↓ Вхідні', exact: true }).click();
  await expect(page.locator('#inbox-sec')).toContainText('Inbox from shade');
  await expect(page.locator('#inbox-sec')).toContainText('... literal ellipsis');
  await expect(page.locator('#inbox-sec')).toContainText('Same title');
  await expect(page.locator('#inbox-sec').getByText('Same title', { exact: true })).toHaveCount(2);
  await page.getByRole('button', { name: '⋯ Ще', exact: true }).click();
  await page.getByRole('button', { name: '📝 Блокнот', exact: true }).click();
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
  expect(saved.xp).toBe(failedState.xp || 0);

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
