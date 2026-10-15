import { test, expect, type Page } from '@playwright/test';

const viewports = [
  { width: 320, height: 360 },
  { width: 390, height: 420 },
];

async function bootEditor(page: Page, viewport: { width: number; height: number }) {
  await page.setViewportSize(viewport);
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    if (!localStorage.getItem('flow_v2')) {
      localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true }));
    }
  });
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
  await page.locator('#add-fab').click();
  await expect(page.locator('#edit-page')).toBeVisible();
}

async function assertSaveTappable(page: Page) {
  const hit = await page.locator('#edit-save-fab').evaluate(button => {
    const rect = button.getBoundingClientRect();
    const x = rect.left + rect.width / 2;
    const y = rect.top + rect.height / 2;
    const top = document.elementFromPoint(x, y);
    return {
      insideViewport: rect.width > 0 && rect.height > 0
        && rect.left >= 0 && rect.top >= 0
        && rect.right <= window.innerWidth && rect.bottom <= window.innerHeight,
      tappable: top === button || (top instanceof Element && button.contains(top)),
      x,
      y,
    };
  });
  expect(hit.insideViewport, 'save button should be fully inside the visible viewport').toBe(true);
  expect(hit.tappable, 'save button center should be the topmost tappable element').toBe(true);
  return hit;
}

async function tapSaveWithoutScrolling(page: Page) {
  const hit = await assertSaveTappable(page);
  await page.mouse.click(hit.x, hit.y);
}

async function setEditorScroll(page: Page, to: 'top' | 'bottom') {
  await page.locator('#edit-page').evaluate((editor, position) => {
    editor.scrollTop = position === 'top' ? 0 : editor.scrollHeight;
  }, to);
}

for (const viewport of viewports) {
  test(`task editor save stays tappable and saves its optional note at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await bootEditor(page, viewport);
    const title = `Short screen save ${viewport.width} ${Date.now()}`;
    await page.getByLabel('Що зробити?').fill(title);
    await page.locator('#task-note-section summary').click();
    await page.locator('#task-note').fill('Note kept after reload');

    await setEditorScroll(page, 'top');
    await assertSaveTappable(page);
    await setEditorScroll(page, 'bottom');
    await assertSaveTappable(page);
    await setEditorScroll(page, 'top');
    await tapSaveWithoutScrolling(page);

    const saved = await page.evaluate(title => {
      const state = JSON.parse(localStorage.getItem('flow_v2') || '{}');
      return state.tasks?.filter((task: any) => task.title === title) || [];
    }, title);
    expect(saved).toHaveLength(1);
    expect(saved[0]).toMatchObject({ title, note: 'Note kept after reload' });

    await page.reload();
    await expect.poll(() => page.evaluate(title => {
      const state = JSON.parse(localStorage.getItem('flow_v2') || '{}');
      return state.tasks?.filter((task: any) => task.title === title).map((task: any) => task.note) || [];
    }, title)).toEqual(['Note kept after reload']);
  });

  test(`task editor validation stays visible and creates no invalid task at ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await bootEditor(page, viewport);

    await setEditorScroll(page, 'top');
    await tapSaveWithoutScrolling(page);
    const titleError = page.getByRole('alert');
    await expect(titleError).toBeVisible();
    await expect(titleError).toContainText('Напиши, що потрібно зробити.');

    await page.getByLabel('Що зробити?').fill('Missing time task');
    await page.getByRole('button', { name: 'На дату й час План і сигнал за розкладом' }).click();
    await page.locator('#task-time').fill('');
    await setEditorScroll(page, 'top');
    await tapSaveWithoutScrolling(page);
    const timeError = page.getByRole('alert');
    await expect(timeError).toBeVisible();
    await expect(timeError).toContainText('Вибери дату й час запланованої задачі.');
    await expect(page.locator('#edit-page')).toBeVisible();

    const invalidTasks = await page.evaluate(() => {
      const state = JSON.parse(localStorage.getItem('flow_v2') || '{}');
      return state.tasks?.filter((task: any) => task.title === 'Missing time task') || [];
    });
    expect(invalidTasks).toHaveLength(0);
  });
}
