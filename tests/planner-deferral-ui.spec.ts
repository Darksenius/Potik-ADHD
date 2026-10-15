import { test, expect } from '@playwright/test';

test('the actual deferred day is visible in Plan and All filters with no obsolete alarm time', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-14T20:00:00+03:00') });
  await page.addInitScript(() => {
    localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true,
      tasks: [{ id: 1, title: 'Підмести завтра ввечері', type: 'sched', zoneId: 5, done: false, someday: false,
        repeat: 'none', repeatDays: [], tags: [], created: '2026-10-14T08:00:00Z',
        schedDate: '2026-10-13', planDate: '2026-10-13', schedTime: '19:00',
        questDeferral: { kind: 'not-today', sourceZoneId: 5, targetZoneId: 5,
          requestedAt: new Date('2026-10-14T19:45:00+03:00').getTime(),
          startAt: new Date('2026-10-15T00:00:00+03:00').getTime() } }],
    }));
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  await page.getByLabel(/Показати/).selectOption('future');
  await expect(page.locator('#tasks-list .tt')).toHaveText('Підмести завтра ввечері');
  await page.getByLabel(/Показати/).selectOption('overdue');
  await expect(page.locator('#tasks-list .tc')).toHaveCount(0);
  await page.getByRole('button', { name: 'План', exact: true }).click();
  const tomorrow = page.getByRole('button', { name: /15 жовтня 2026/ });
  await expect(tomorrow).toHaveAttribute('aria-label', /є задачі/);
  await tomorrow.click();
  const row = page.locator('.plan-task-row').filter({ has: page.getByRole('button', { name: 'Підмести завтра ввечері', exact: true }) });
  await expect(row).toContainText('Перенесено');
  await expect(row).not.toContainText('19:00');
});

test('a future interval occurrence cannot undo the past completion and credits only when due', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-14T20:00:00+03:00') });
  await page.addInitScript(() => {
    localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true,
      done: 1, xp: 10, xpTotal: 10,
      tasks: [{ id: 2, title: 'Перевірити пошту', type: 'simple', done: true, completionCredited: true,
        doneDate: '2026-10-14', completionCreditDate: '2026-10-14', counterDay: '2026-10-14', planDate: '2026-10-14',
        someday: false, repeat: 'interval', repeatInterval: 1, repeatUnit: 'hour', repeatMs: 3600000,
        nextRepeatAt: new Date('2026-10-15T09:00:00+03:00').getTime(), repeatDays: [], tags: [],
        created: '2026-10-14T08:00:00Z' }],
    }));
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'План', exact: true }).click();
  await page.getByRole('button', { name: /15 жовтня 2026/ }).click();
  const next = page.getByRole('button', { name: 'Виконати: Перевірити пошту', exact: true });
  await expect(next).toBeDisabled();
  await expect(next).not.toHaveClass(/\bon\b/);
  await page.clock.setSystemTime(new Date('2026-10-15T08:30:00+03:00'));
  await page.clock.runFor(1100);
  await expect(next).toBeDisabled();
  await page.clock.setSystemTime(new Date('2026-10-15T09:00:00+03:00'));
  await page.clock.runFor(1100);
  await expect(next).toBeEnabled();
  await next.click();
  await page.clock.runFor(700);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.done).toBe(2);
  expect(saved.tasks[0]).toMatchObject({ done: true, doneDate: '2026-10-15' });
  expect(saved.tasks[0].nextRepeatAt).toBeGreaterThan(new Date('2026-10-15T09:59:00+03:00').getTime());
});
