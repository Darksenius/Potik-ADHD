import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

// Use the runtime actually embedded in the APK. Its legacy Plugins listener
// returns a synchronous handle, unlike the Promise-based registerPlugin API.
const nativeRuntime = readFileSync(
  'node_modules/@capacitor/android/capacitor/src/main/assets/native-bridge.js', 'utf8',
);

const previousState = {
  saveDate: 'Wed Sep 16 2026',
  tasks: [{ id: 401, title: 'Збережена задача', type: 'simple', done: false,
    someday: false, repeat: 'none', tags: [], planDate: '2026-09-16', created: '2026-09-16T09:00:00.000Z' }],
  qnotes: [{ id: 501, txt: 'Збережена нотатка', folder: 'impulse', date: '2026-09-15', time: '09:00' }],
  notepad: 'Мій блокнот', xp: 31, xpTotal: 731, level: 4,
};

for (const saved of [false, true]) {
  test(`real Android bridge opens the UI with ${saved ? 'existing native data' : 'no previous data'}`, async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.clock.install({ time: new Date('2026-09-16T12:00:00+03:00') });
    await page.addInitScript({ content: `
      window.__nativeState = ${JSON.stringify(saved ? JSON.stringify(previousState) : '')};
      window.__nativeCalls = [];
      window.__nativeEvents = [];
      window.FlowBridge = {
        load: () => window.__nativeState,
        save: value => { window.__nativeState = value; },
        saveNotif: () => {}, writeBackup: () => {},
        hasBackup: () => false, readBackup: () => '', appVersion: () => '1.7.5.r3'
      };
      window.androidBridge = { postMessage(message) {
        const call = JSON.parse(message);
        window.__nativeCalls.push(call);
        if (call.methodName === 'addListener') {
          window.__nativeListener = call;
          return;
        }
        const data = call.methodName === 'drainEvents'
          ? { events: JSON.stringify(window.__nativeEvents.splice(0)) } : {};
        Promise.resolve().then(() => window.Capacitor.fromNative({
          ...call, success: true, data
        }));
      }};
      ${nativeRuntime}
      // Same wrappers emitted by Capacitor's JSExport.java for FlowPlugin.
      window.Capacitor.Plugins = window.Capacitor.Plugins || {};
      window.Capacitor.Plugins.FlowNotif = {
        addListener: (name, callback) => window.Capacitor.addListener('FlowNotif', name, callback),
        start: () => window.Capacitor.nativePromise('FlowNotif', 'start', {}),
        update: () => window.Capacitor.nativePromise('FlowNotif', 'update', {}),
        stop: () => window.Capacitor.nativePromise('FlowNotif', 'stop', {}),
        drainEvents: () => window.Capacitor.nativePromise('FlowNotif', 'drainEvents', {})
      };
    ` });
    await page.goto('/');
    expect(errors, 'Native bootstrap must not throw before React mounts').toEqual([]);
    await expect(page.locator('#add-fab')).toBeVisible();
    await page.clock.runFor(2000);
    expect(await page.evaluate(() => (window as any).__nativeCalls.map((call: any) => call.methodName)))
      .toEqual(expect.arrayContaining(['addListener', 'start', 'drainEvents']));

    if (saved) {
      await expect(page.getByText('Збережена задача', { exact: true })).toBeVisible();
      const state = await page.evaluate(() => JSON.parse((window as any).__nativeState));
      expect(state).toMatchObject(previousState);
      // Deliver a notification action through the real native callback registry.
      await page.evaluate(() => {
        const w = window as any;
        w.__nativeEvents.push('task_done:401');
        w.Capacitor.fromNative({ ...w.__nativeListener, success: true, save: true, data: {} });
      });
      await expect.poll(() => page.evaluate(() =>
        JSON.parse((window as any).__nativeState).tasks.find((task: any) => task.id === 401).done,
      )).toBe(true);
    }
    expect(errors).toEqual([]);
  });
}
