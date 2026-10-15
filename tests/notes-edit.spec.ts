import { test, expect, type Page } from '@playwright/test';

const originalText = 'Зберегти думку 🌿\nДругий абзац';

async function openNotebook(page: Page) {
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
}

async function boot(page: Page) {
  await page.clock.install({ time: new Date('2026-10-13T12:00:00+03:00') });
  await page.addInitScript(text => {
    if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({
      saveDate: new Date().toDateString(), weekTplSeeded: true, tasks: [],
      qnotes: [{ id: 401, txt: text, folder: 'impulse', date: '2026-10-13', time: '11:00' }], qnid: 402,
    }));
  }, originalText);
  await page.goto('/');
  await openNotebook(page);
}

async function savedNote(page: Page) {
  return page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!).qnotes.find((note: { id: number }) => note.id === 401));
}

test('standalone note preserves multiline text and folder after explicit save and reload', async ({ page }, info) => {
  await boot(page);
  await page.setViewportSize({ width: 320, height: 800 });
  await page.getByRole('button', { name: 'Редагувати нотатку', exact: true }).click();
  const text = page.getByRole('textbox', { name: 'Текст нотатки', exact: true });
  await expect(text).toHaveValue(originalText);
  await text.fill('Перший рядок');
  await text.press('End');
  await text.press('Enter');
  await text.pressSequentially('... це досі нотатка');
  const edited = 'Перший рядок\n... це досі нотатка';
  await expect(text).toHaveValue(edited);
  const form = page.getByRole('form', { name: 'Редагувати нотатку' });
  await form.getByLabel('Папка', { exact: true }).selectOption('work');
  await page.clock.runFor(700);
  expect(await savedNote(page)).toMatchObject({ id: 401, txt: originalText, folder: 'impulse' });
  await expect(form).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.screenshot({ path: info.outputPath('note-editor-320.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: 'Зберегти нотатку', exact: true }).click();
  await page.clock.runFor(700);
  expect(await savedNote(page)).toMatchObject({ id: 401, txt: edited, folder: 'work', date: '2026-10-13', time: '11:00' });
  await page.reload();
  await openNotebook(page);
  await expect(page.locator('.qn-txt')).toHaveText(edited);
  await page.getByRole('button', { name: 'Редагувати нотатку', exact: true }).click();
  await expect(text).toHaveValue(edited);
  await expect(page.getByRole('form', { name: 'Редагувати нотатку' }).getByLabel('Папка', { exact: true })).toHaveValue('work');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!).tasks)).toEqual([]);
});

test('cancel and Escape discard note drafts while Ctrl+Enter explicitly saves', async ({ page }) => {
  await boot(page);
  const edit = page.getByRole('button', { name: 'Редагувати нотатку', exact: true });
  const text = page.getByRole('textbox', { name: 'Текст нотатки', exact: true });
  const folder = page.getByRole('form', { name: 'Редагувати нотатку' }).getByLabel('Папка', { exact: true });
  await edit.click();
  await text.fill('Відкинути\nцю зміну');
  await folder.selectOption('therapy');
  await page.getByRole('button', { name: 'Скасувати', exact: true }).click();
  await expect(edit).toBeFocused();
  await page.clock.runFor(700);
  expect(await savedNote(page)).toMatchObject({ txt: originalText, folder: 'impulse' });
  await edit.click();
  await expect(text).toHaveValue(originalText);
  await expect(folder).toHaveValue('impulse');
  await text.fill('Ще одна незбережена зміна');
  await text.press('Escape');
  await page.reload();
  await openNotebook(page);
  await edit.click();
  await expect(text).toHaveValue(originalText);
  await text.fill('');
  await expect(page.getByRole('button', { name: 'Зберегти нотатку', exact: true })).toBeDisabled();
  await text.fill('Збережено клавіатурою\nДруга лінія');
  await folder.selectOption('general');
  await text.press('Control+Enter');
  await expect(text).toHaveCount(0);
  await page.clock.runFor(700);
  await page.reload();
  await openNotebook(page);
  expect(await savedNote(page)).toMatchObject({ txt: 'Збережено клавіатурою\nДруга лінія', folder: 'general' });
});
