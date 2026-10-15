import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { handleNativeEvent } from '../src/bridge/nativeBridge';
import { collectState, applyState } from '../src/services/persistence';

test('a closed-app capture and Done from yesterday commit once after midnight', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-15T08:00:00+03:00') });
  await page.addInitScript(() => {
    const createdAt = new Date('2026-10-14T21:00:00+03:00').getTime();
    const taskId = createdAt * 1024;
    const mock = (window as any).__lateCapture = {
      state: JSON.stringify({ saveDate: new Date().toDateString(), tasks: [], weekTplSeeded: true }),
      pending: [
        { id: 1, event: 'capture_task_v2:' + taskId + ':' + JSON.stringify({ title: 'Учора зі шторки', createdAt }), createdAt },
        { id: 2, event: 'task_done:' + taskId, createdAt: createdAt + 60000 },
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
  expect(result.saved.qnotes || []).toHaveLength(0);
  expect(result.pending).toHaveLength(0);
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
