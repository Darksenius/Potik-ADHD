import { test, expect } from '@playwright/test';

test('an undated simple task is a normal task in Now and All Tasks, with its earned XP at the tap', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-08T12:00:00+03:00') });
  await page.goto('/');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill('Не забути зарядку');
  await page.locator('#edit-save-fab').click();

  const card = page.locator('#tasks-list .tc').filter({ hasText: 'Не забути зарядку' });
  await expect(card).toBeVisible();
  await expect(card).toContainText('Без дати');
  await page.getByRole('button', { name: 'Зараз', exact: true }).click();
  await expect(page.locator('#tasks-list .tt')).toHaveText('Не забути зарядку');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  const allTasksCard = page.locator('#tasks-list .tc').filter({ hasText: 'Не забути зарядку' });
  const checkbox = allTasksCard.getByRole('button', { name: 'Виконати: Не забути зарядку', exact: true });
  const tap = await checkbox.boundingBox();
  expect(tap).not.toBeNull();
  await checkbox.click({ position: { x: 24, y: 24 } });

  const reward = page.locator('.task-reward');
  await expect(reward).toHaveText('+10⚡');
  const origin = await reward.evaluate(element => ({ left: parseFloat((element as HTMLElement).style.left), top: parseFloat((element as HTMLElement).style.top) }));
  expect(Math.abs(origin.left - (tap!.x + 24))).toBeLessThan(3);
  expect(Math.abs(origin.top - (tap!.y + 24))).toBeLessThan(3);
  await expect(reward).toBeHidden({ timeout: 2500 });

  await expect(allTasksCard).toHaveClass(/done-t/);
});

test('task completion checkbox keeps a true circular hit target and the redundant inbox route is gone', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Усі задачі', exact: true }).click();
  await page.locator('#add-fab').click();
  await page.getByLabel('Що зробити?').fill('Коло');
  await page.locator('#edit-save-fab').click();
  const checkbox = page.getByRole('button', { name: 'Виконати: Коло', exact: true });
  const shape = await checkbox.evaluate(element => {
    const style = getComputedStyle(element);
    return { width: style.width, height: style.height, radius: style.borderRadius };
  });
  expect(shape.width).toBe(shape.height);
  expect(shape.radius).toBe('50%');

  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Вхідні', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Усі задачі', exact: true })).toBeVisible();
});
