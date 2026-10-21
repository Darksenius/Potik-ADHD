import { test, expect, type Locator, type Page } from '@playwright/test';
import { useStore } from '../src/state/store';
import { importState } from '../src/services/exportImport';

const multiline = 'Перша лінія — українською 🌿\nДруга лінія: 1) дія; 2) дія.';

async function boot(page: Page) {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.addInitScript(() => {
    if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true }));
  });
  await page.goto('/');
  await expect(page.locator('#add-fab')).toBeVisible();
}

async function selectTaskType(page: Page, type: string) {
  const select = page.locator('#task-type');
  if (!(await select.isVisible())) await page.locator('.editor-type-section summary').click();
  await page.getByLabel('Тип задачі', { exact: true }).selectOption(type);
}

async function openAllTasks(page: Page) {
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
}

async function savedTask(page: Page, title: string) {
  return page.evaluate(title => JSON.parse(localStorage.getItem('flow_v2')!).tasks.find((task: any) => task.title === title), title);
}

async function expandIfNeeded(card: Locator, title: string) {
  const expand = card.getByRole('button', { name: 'Розгорнути: ' + title, exact: true });
  if (await expand.count()) await expand.click();
}

test('baseline: existing note task creates Today, reloads, edits inline, and reloads again', async ({ page }) => {
  await boot(page);
  const title = 'Baseline note ' + Date.now();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill(title);
  await page.getByText('Додаткові можливості', { exact: true }).click();
  await selectTaskType(page, 'note');
  const editorNote = page.locator('#task-note, #edit-body textarea').first();
  await editorNote.fill(multiline);
  await page.locator('#edit-save-fab').click();
  await expect(page.locator('#tasks-list')).toContainText(title);

  let task = await savedTask(page, title);
  expect(task.type).toBe('note');
  expect(task.note).toBe(multiline);
  const taskId = task.id;
  await page.reload();
  const card = page.locator('.tc').filter({ has: page.getByRole('button', { name: title, exact: true }) });
  await expandIfNeeded(card, title);
  const inlineNote = page.locator('.tc textarea.tnote, #task-note-' + taskId).first();
  await expect(inlineNote).toHaveValue(multiline);
  const edited = 'Змінено після відкриття\nНова лінія ✨';
  await inlineNote.fill(edited);
  await page.clock.runFor(700);
  await page.reload();
  const afterReload = page.locator('.tc').filter({ has: page.getByRole('button', { name: title, exact: true }) });
  await expandIfNeeded(afterReload, title);
  await expect(page.locator('.tc textarea.tnote, #task-note-' + taskId).first()).toHaveValue(edited);
});

test('saveTask preserves optional notes for simple and scheduled tasks, and backup import retains them', () => {
  useStore.setState(useStore.getInitialState(), true);
  const simpleId = useStore.getState().saveTask(null, { title: 'Simple note', type: 'simple', note: multiline });
  const scheduledId = useStore.getState().saveTask(null, {
    title: 'Scheduled note', type: 'sched', planDate: '2026-10-09', schedDate: '2026-10-09', schedTime: '10:30', note: multiline,
  });
  const existingNoteId = useStore.getState().saveTask(null, { title: 'Existing note type', type: 'note', note: multiline });
  useStore.getState().saveTask(existingNoteId, { title: 'Existing note type', type: 'simple', note: multiline });
  const simple = useStore.getState().tasks.find(task => task.id === simpleId)!;
  const scheduled = useStore.getState().tasks.find(task => task.id === scheduledId)!;
  const changedType = useStore.getState().tasks.find(task => task.id === existingNoteId)!;
  expect.soft(simple.note).toBe(multiline);
  expect.soft(scheduled.note).toBe(multiline);
  expect.soft(changedType).toMatchObject({ type: 'simple', note: multiline });

  const backupTasks = [simple, scheduled, changedType].map(task => ({ ...task, note: multiline }));
  const storage = new Map<string, string>();
  (globalThis as any).localStorage = { getItem: (key: string) => storage.get(key) || null, setItem: (key: string, value: string) => storage.set(key, value) };
  (globalThis as any).window = {};
  try {
    useStore.setState(useStore.getInitialState(), true);
    expect(importState({ tasks: backupTasks }, 'restore')).toBe(3);
    expect(useStore.getState().tasks).toEqual(expect.arrayContaining([
      expect.objectContaining({ title: 'Simple note', type: 'simple', note: multiline }),
      expect.objectContaining({ title: 'Scheduled note', type: 'sched', note: multiline }),
      expect.objectContaining({ title: 'Existing note type', type: 'simple', note: multiline }),
    ]));
  } finally {
    useStore.setState(useStore.getInitialState(), true);
    delete (globalThis as any).window;
    delete (globalThis as any).localStorage;
  }
});

test('simple optional note survives type changes, reload, inline edit, and intentional clearing', async ({ page }, info) => {
  await boot(page);
  await page.setViewportSize({ width: 320, height: 800 });
  const title = 'Simple note ' + Date.now();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill(title);
  await page.getByRole('button', { name: 'Без дати', exact: true }).click();
  await expect(page.locator('.schedule-preview')).toContainText('Без дати: задача доступна');
  await page.locator('#task-note-section summary').click();
  await page.locator('#task-note').fill(multiline);
  await page.screenshot({ path: info.outputPath('notes-editor-320.png'), fullPage: true, animations: 'disabled' });
  await page.getByText('Додаткові можливості', { exact: true }).click();
  await selectTaskType(page, 'note');
  await expect(page.locator('#task-note')).toHaveCount(1);
  await expect(page.locator('#task-note')).toHaveValue(multiline);
  await selectTaskType(page, 'simple');
  await expect(page.locator('#task-note')).toHaveValue(multiline);
  await page.locator('#edit-save-fab').click();

  let task = await savedTask(page, title);
  expect(task).toMatchObject({ type: 'simple', note: multiline });
  expect(task.planDate).toBeFalsy();
  expect(task.alarmTime).toBeFalsy();
  expect(task.schedDate).toBeFalsy();
  const taskId = task.id;
  await page.reload();
  await openAllTasks(page);
  const card = page.locator('.tc').filter({ has: page.getByRole('button', { name: title, exact: true }) });
  await expandIfNeeded(card, title);
  const note = page.locator('#task-note-' + taskId);
  await expect(note).toHaveValue(multiline);
  await page.screenshot({ path: info.outputPath('notes-card-expanded-320.png'), fullPage: true, animations: 'disabled' });
  const changed = 'Оновлена думка\nбез втрати абзацу 📚';
  await note.fill(changed);
  await page.clock.runFor(700);
  await page.reload();
  await openAllTasks(page);
  const reopened = page.locator('.tc').filter({ has: page.getByRole('button', { name: title, exact: true }) });
  await expandIfNeeded(reopened, title);
  await expect(page.locator('#task-note-' + taskId)).toHaveValue(changed);
  const noteAfterEdit = page.locator('#task-note-' + taskId);
  await noteAfterEdit.fill('');
  await expect(noteAfterEdit).toBeVisible();
  await expect(noteAfterEdit).toHaveValue('');
  await page.clock.runFor(700);
  expect((await savedTask(page, title)).note).toBe('');
  await page.reload();
  await openAllTasks(page);
  const clearedCard = page.locator('.tc').filter({ has: page.getByRole('button', { name: title, exact: true }) });
  await expandIfNeeded(clearedCard, title);
  await expect(page.locator('#task-note-' + taskId)).toBeVisible();
  await expect(page.locator('#task-note-' + taskId)).toHaveValue('');
  expect((await savedTask(page, title)).note).toBe('');
});

test('scheduled optional note opens from its calendar day with the same ID and survives conversion to simple', async ({ page }) => {
  await boot(page);
  const title = 'Scheduled note ' + Date.now();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill(title);
  await page.getByRole('button', { name: 'На дату й час План і сигнал за розкладом' }).click();
  await page.getByLabel('Дата *', { exact: true }).fill('2026-10-09');
  await page.getByLabel('Час *', { exact: true }).fill('14:30');
  await page.locator('#task-note-section summary').click();
  await page.locator('#task-note').fill(multiline);
  await page.locator('#edit-save-fab').click();
  const original = await savedTask(page, title);
  expect(original).toMatchObject({ type: 'sched', planDate: '2026-10-09', schedDate: '2026-10-09', schedTime: '14:30', note: multiline });
  expect(original.alarmTime).toBeFalsy();

  await page.getByRole('button', { name: 'План', exact: true }).click();
  const day = page.getByRole('button', { name: '9 жовтня 2026 р., є задачі', exact: true });
  await day.click();
  await page.getByRole('button', { name: title, exact: true }).click();
  await expect(page.locator('#task-note-section')).toHaveJSProperty('open', true);
  await expect(page.locator('#task-note')).toHaveValue(multiline);
  await page.getByRole('button', { name: 'Без точного часу У списку задач, коли підходять умови', exact: true }).click();
  await page.getByRole('button', { name: 'Без дати', exact: true }).click();
  await expect(page.locator('#task-note')).toHaveValue(multiline);
  await page.locator('#edit-save-fab').click();

  const converted = await savedTask(page, title);
  expect(converted.id).toBe(original.id);
  expect(converted).toMatchObject({ type: 'simple', note: multiline });
  expect(converted.planDate).toBeFalsy();
  expect(converted.schedDate).toBeFalsy();
  expect(converted.schedTime).toBeFalsy();
  await page.reload();
  const reloaded = await savedTask(page, title);
  expect(reloaded).toMatchObject({ id: original.id, type: 'simple', note: multiline });
});
