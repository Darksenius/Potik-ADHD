import { test, expect } from '@playwright/test';

test('native commands are committed with state once; failed commit keeps the queue and rolls back UI', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    const mock = (window as any).__commands = { fail: true, pending: [{ id: 7, event: 'note:Не загубити з черги', createdAt: Date.now() }], commits: [] as string[] };
    (window as any).FlowBridge = {
      load: () => JSON.stringify({ saveDate: new Date().toDateString(), tasks: [], qnotes: [], weekTplSeeded: true }),
      save: () => {}, saveNotif: () => {}, writeBackup: () => {}, readBackup: () => '', hasBackup: () => false,
      saveWithEvents: (json: string, ids: string) => {
        if (mock.fail) return false;
        const ack = JSON.parse(ids);
        if (ack.length) mock.commits.push(json);
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
  expect(await page.evaluate(() => (window as any).__commands.pending.length)).toBe(1);
  await page.getByRole('button', { name: '⋯ Ще', exact: true }).click();
  await page.getByRole('button', { name: '📝 Блокнот', exact: true }).click();
  await expect(page.locator('#qn-list')).not.toContainText('Не загубити з черги');
  await page.evaluate(() => { (window as any).__commands.fail = false; window.__flowDrainEvents?.(); window.__flowDrainEvents?.(); });
  await expect(page.locator('#qn-list')).toContainText('Не загубити з черги');
  await page.evaluate(() => window.__flowDrainEvents?.());
  const result = await page.evaluate(() => (window as any).__commands);
  expect(result.pending).toEqual([]);
  expect(result.commits).toHaveLength(1);
  expect(JSON.parse(result.commits[0]).qnotes.filter((n: any) => n.txt === 'Не загубити з черги')).toHaveLength(1);
});
