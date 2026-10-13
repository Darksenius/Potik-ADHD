import { test, expect } from '@playwright/test';

test('create a zoned checklist with steps and note, count a quest, then reload the real editor data', async ({ page }, info) => {
  await page.clock.install({ time: new Date('2026-10-14T18:30:00+03:00') });
  await page.goto('/');
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?', { exact: true }).fill('Прибрати перед сном');
  await page.getByLabel('У якій зоні?', { exact: true }).selectOption('5');
  await page.locator('#task-note-section summary').click();
  await page.getByLabel('Нотатка до задачі', { exact: true }).fill('Почати з одного кута.');
  await page.getByText('Додаткові можливості', { exact: true }).click();
  await page.getByLabel('Тип задачі', { exact: true }).selectOption('check');
  await page.locator('#task-checklist').fill('Підмести\nВинести сміття');
  await page.locator('#edit-save-fab').click();
  await expect(page.getByRole('checkbox', { name: 'Підмести', exact: true })).toBeVisible();
  await page.getByRole('checkbox', { name: 'Підмести', exact: true }).check();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?', { exact: true }).fill('П’ять хвилин руху');
  await page.getByText('Додаткові можливості', { exact: true }).click();
  await page.getByLabel('Тип задачі', { exact: true }).selectOption('counter');
  await page.getByLabel('Ціль лічильника (0 — без цілі)', { exact: true }).fill('2');
  await page.locator('#edit-save-fab').click();
  await page.getByRole('button', { name: 'Розгорнути: П’ять хвилин руху', exact: true }).click();
  await page.getByRole('button', { name: 'Збільшити: П’ять хвилин руху', exact: true }).click();
  await page.clock.runFor(1600);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Виконати: Прибрати перед сном', exact: true })).toBeVisible();
  const data = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(data.tasks.find((task: any) => task.title === 'Прибрати перед сном')).toMatchObject({
    zoneId: 5, note: 'Почати з одного кута.', items: [{ text: 'Підмести', done: true }, { text: 'Винести сміття', done: false }],
  });
  expect(data.tasks.find((task: any) => task.title === 'П’ять хвилин руху')).toMatchObject({ counter: 1, done: false });
  await page.screenshot({ path: info.outputPath('quest-panel.png'), fullPage: true });
  await page.getByRole('button', { name: 'Налаштування', exact: true }).click();
  await page.screenshot({ path: info.outputPath('compact-settings.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  await page.getByText('Вигляд', { exact: true }).click();
  await page.getByLabel('Тема', { exact: true }).selectOption('light');
  await page.getByRole('button', { name: 'Потік', exact: true }).click();
  await page.screenshot({ path: info.outputPath('quest-panel-light.png'), fullPage: true });
});
