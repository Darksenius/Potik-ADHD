import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { handleNativeEvent } from '../src/bridge/nativeBridge';
import { collectState, applyState } from '../src/services/persistence';

test('a closed-app capture and Done from yesterday commit once after midnight', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-15T08:00:00+03:00') });
  await page.addInitScript(() => {
    const createdAt = Date.now() - 30 * 60 * 60 * 1000;
    const taskId = createdAt * 1024;
    const mock = (window as any).__lateCapture = {
      state: JSON.stringify({ saveDate: new Date().toDateString(), tasks: [], weekTplSeeded: true }),
      pending: [
        { id: 1, event: 'capture_task_v2:' + taskId + ':' + JSON.stringify({ title: 'Учора зі шторки', createdAt }), createdAt },
        { id: 2, event: 'task_done:' + taskId, createdAt: createdAt + 60000 },
        { id: 3, event: 'capture_note:Учорашня нотатка', createdAt: createdAt + 120000 },
      ],
    };
    (window as any).FlowBridge = {
      load: () => mock.state, readBackup: () => '', hasBackup: () => false,
      saveNotif: () => {}, writeBackup: () => {},
      saveWithEvents: (raw: string, ids: string) => {
        mock.state = raw;
        const acknowledged = JSON.parse(ids);
        mock.pending = mock.pending.filter(e => !acknowledged.includes(e.id));
        return true;
      },
    };
    (window as any).Capacitor = { isNativePlatform: () => true, Plugins: { FlowNotif: {
      addListener: () => ({ remove: async () => {} }), start: async () => {}, update: async () => {},
      readEvents: async () => ({ events: JSON.stringify(mock.pending) }),
    } } };
  });
  await page.goto('/');
  await expect.poll(() => page.evaluate(() => JSON.parse((window as any).__lateCapture.state).tasks))
    .toMatchObject([{ title: 'Учора зі шторки', done: true }]);
  const result = await page.evaluate(() => ({
    saved: JSON.parse((window as any).__lateCapture.state), pending: (window as any).__lateCapture.pending,
  }));
  expect(result.saved.tasks).toHaveLength(1);
  expect(result.saved.qnotes).toMatchObject([{ txt: 'Учорашня нотатка', date: '2026-10-14' }]);
  expect(result.saved.progressDays).toEqual(['2026-10-14']);
  expect(result.saved.dailyInsightClaims).toMatchObject([{ day: '2026-10-14', earnedAt: 0 }]);
  expect(result.pending).toHaveLength(0);

  await page.evaluate(() => {
    const mock = (window as any).__lateCapture;
    const createdAt = Date.now();
    mock.pending = [{ id: 4, event: 'capture_note:Сьогоднішня дія', createdAt }];
    (window as any).__flowDrainEvents();
  });
  await expect.poll(() => page.evaluate(() => JSON.parse((window as any).__lateCapture.state).dailyInsightClaims))
    .toMatchObject([{ day: '2026-10-14', earnedAt: 0 }, { day: '2026-10-15', earnedAt: expect.any(Number) }]);
  const afterToday = await page.evaluate(() => JSON.parse((window as any).__lateCapture.state));
  expect(afterToday.progressDays).toEqual(['2026-10-14', '2026-10-15']);
  expect(afterToday.qnotes).toContainEqual(expect.objectContaining({ txt: 'Сьогоднішня дія', date: '2026-10-15' }));
});

test('a native quick capture keeps its id through pre-open actions and JSON reload without duplicates', () => {
  useStore.setState(useStore.getInitialState(), true);
  const id = 1830000000000128;
  const createdAt = Date.now() - 60000;
  const title = '... думка: список\nДругий рядок';
  const capture = 'capture_task_v2:' + id + ':' + JSON.stringify({ title, createdAt });
  expect(handleNativeEvent(capture, createdAt)).toBe(true);
  expect(handleNativeEvent(capture, createdAt)).toBe(true);
  expect(useStore.getState().tasks.filter(task => task.id === id)).toHaveLength(1);
  expect(useStore.getState().tasks[0]).toMatchObject({ id, title, type: 'simple' });
  const requestedAt = createdAt + 30000;
  const deferral = { kind: 'not-today', requestedAt, sourceZoneId: 0, targetZoneId: 0, startAt: requestedAt + 23 * 60 * 60 * 1000 };
  expect(handleNativeEvent('task_defer:' + id + ':' + JSON.stringify(deferral), requestedAt)).toBe(true);
  expect(useStore.getState().tasks[0].questDeferral).toEqual(deferral);
  const saved = JSON.parse(JSON.stringify(collectState()));
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(saved, true)).toBe(true);
  expect(useStore.getState().tasks[0]).toMatchObject({ id, title, questDeferral: deferral });
  expect(useStore.getState().nid).toBeGreaterThan(id);
  expect(handleNativeEvent('task_done:' + id, requestedAt + 1)).toBe(true);
  expect(useStore.getState().tasks[0].done).toBe(true);
  expect(useStore.getState().tasks[0].questDeferral).toBeUndefined();
  useStore.setState(useStore.getInitialState(), true);
});
