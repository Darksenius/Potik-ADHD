import { test, expect } from '@playwright/test';

for (const completed of [false, true]) test(completed
  ? 'a deferred Done from yesterday credits yesterday and leaves today a fresh daily occurrence'
  : 'a deferred daily checklist keeps progress when the app reopens the next evening', async ({ page }) => {
  await page.clock.install({ time: new Date(completed ? '2026-10-16T08:00:00+03:00' : '2026-10-15T18:30:00+03:00') });
  await page.addInitScript(completed => {
    const requestedAt = new Date('2026-10-14T19:00:00+03:00').getTime();
    const deferral = { kind: 'not-today', requestedAt, sourceZoneId: 5, targetZoneId: 5,
      startAt: new Date('2026-10-15T00:00:00+03:00').getTime() };
    const mock = (window as any).__deferredQueue = {
      state: JSON.stringify({ saveDate: new Date(requestedAt).toDateString(), weekTplSeeded: true,
        tasks: [{ id: 501, title: 'Не втратити кроки', type: 'check', done: false, someday: false,
          repeat: 'daily', repeatDays: [], zoneId: 5, planDate: '2026-10-14', counterDay: '2026-10-14',
          created: '2026-10-14T08:00:00Z', items: [{ text: 'Підмести', done: true, credited: true },
            { text: 'Винести сміття', done: false }] }],
      }),
      events: [{ id: 1, event: 'task_defer:501:' + JSON.stringify(deferral), createdAt: requestedAt }],
    };
    if (completed) mock.events.push({ id: 2, event: 'task_done:501',
      createdAt: new Date('2026-10-15T19:00:00+03:00').getTime() });
    (window as any).FlowBridge = {
      load: () => mock.state, getEventEnvelopes: () => JSON.stringify(mock.events),
      readBackup: () => '', hasBackup: () => false, writeBackup: () => {}, saveNotif: () => {},
      saveWithEvents: (raw: string, ids: string) => {
        mock.state = raw;
        const ack = JSON.parse(ids);
        mock.events = mock.events.filter(e => !ack.includes(e.id));
        return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.events) }),
    } } };
  }, completed);
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => (window as any).__deferredQueue.events.length)).toBe(0);
  const saved = await page.evaluate(() => JSON.parse((window as any).__deferredQueue.state));
  if (completed) {
    expect(saved.done).toBe(1);
    expect(saved.qnotes || []).toHaveLength(0);
    expect(saved.planDayLog['2026-10-15'].tasksDone).toBe(1);
    expect(saved.tasks[0]).toMatchObject({ done: false, counterDay: '2026-10-16' });
    expect(saved.tasks[0].items).toMatchObject([{ done: false }, { done: false }]);
  } else {
    expect(saved.tasks[0].items).toEqual([{ text: 'Підмести', done: true, credited: true },
      { text: 'Винести сміття', done: false }]);
    expect(saved.tasks[0].questDeferral.kind).toBe('not-today');
    await expect(page.getByRole('button', { name: 'Не втратити кроки', exact: true })).toBeVisible();
  }
});

test('a one-off window completed from the shade stays completed after a midnight cold start', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-16T08:00:00+03:00') });
  await page.addInitScript(() => {
    const at = new Date('2026-10-15T19:00:00+03:00').getTime();
    const mock = (window as any).__windowQueue = {
      state: JSON.stringify({ saveDate: new Date(at).toDateString(), tasks: [{
        id: 501, title: 'Одне вечірнє виконання', type: 'timewin', repeat: 'none', done: false,
        created: '2026-10-14T08:00:00Z', planDate: '2026-10-15', windowStart: '18:00', windowEnd: '22:00',
      }] }), events: [{ id: 1, event: 'task_done:501', createdAt: at }],
    };
    (window as any).FlowBridge = {
      load: () => mock.state, getEventEnvelopes: () => JSON.stringify(mock.events),
      readBackup: () => '', hasBackup: () => false, writeBackup: () => {}, saveNotif: () => {},
      saveWithEvents: (raw: string, ids: string) => {
        mock.state = raw; const ack = JSON.parse(ids); mock.events = mock.events.filter(e => !ack.includes(e.id)); return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.events) }),
    } } };
  });
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => (window as any).__windowQueue.events.length)).toBe(0);
  const saved = await page.evaluate(() => JSON.parse((window as any).__windowQueue.state));
  expect(saved.tasks[0]).toMatchObject({ done: true, doneDate: '2026-10-15', planDate: '2026-10-15', repeat: 'none' });
  expect(saved.qnotes || []).toHaveLength(0);
  expect(saved.taskHistory.filter((e: { kind: string }) => e.kind === 'completed')).toHaveLength(1);
});
