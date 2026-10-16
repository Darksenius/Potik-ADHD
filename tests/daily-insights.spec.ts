import { test, expect } from '@playwright/test';
import { archiveInsightClaims, claimDailyInsight, DAILY_INSIGHTS, INSIGHT_SOURCES } from '../src/utils/dailyInsights';
import { useStore } from '../src/state/store';
import { collectState, applyState } from '../src/services/persistence';
import { buildForegroundNotifPayload } from '../src/bridge/notifPayload';
import { fmtDate } from '../src/utils/date';

test('the collection has 100 distinct cards with valid source references', () => {
  expect(DAILY_INSIGHTS.map(card => card.id)).toEqual(Array.from({ length: 100 }, (_, index) => index + 1));
  expect(new Set(DAILY_INSIGHTS.map(card => card.title)).size).toBe(100);
  for (const card of DAILY_INSIGHTS) {
    expect(card.title.length).toBeLessThanOrEqual(160);
    expect(card.text.length).toBeGreaterThan(40);
    expect(card.text.length).toBeLessThanOrEqual(2000);
    expect(card.sources.every(id => INSIGHT_SOURCES.some(source => source.id === id))).toBe(true);
  }
});

test('gaps do not reset the collection, duplicate actions do not earn cards, and imports do not renumber them', () => {
  const now = new Date('2026-10-16T12:00:00+03:00').getTime();
  const days = ['2026-10-14', '2026-10-16'];
  const first = claimDailyInsight(days, [], now, now);
  expect(first.earned).toMatchObject({ day: '2026-10-16', cardId: 2, sequence: 2 });
  const again = claimDailyInsight(days, first.claims, now + 1000, now + 1000);
  expect(again.earned).toBeNull();
  expect(again.claims).toBe(first.claims);
  const merged = archiveInsightClaims(['2026-10-13', ...days], first.claims, now);
  expect(merged.find(claim => claim.day === '2026-10-16')).toEqual(first.earned);
  const tomorrow = now + 86400000;
  const next = claimDailyInsight([...days, '2026-10-17'], first.claims, tomorrow, tomorrow);
  expect(next.earned).toMatchObject({ cardId: 3, sequence: 3 });
});

test('opening and migration do not issue a new reward; the 101st active day starts another circle', () => {
  const now = new Date('2026-10-16T12:00:00+03:00').getTime();
  const days = Array.from({ length: 101 }, (_, index) => fmtDate(new Date(now - (100 - index) * 86400000)));
  const archived = archiveInsightClaims(days, [], now);
  expect(archived).toHaveLength(100);
  expect(archived.every(claim => claim.earnedAt === 0)).toBe(true);
  const next = claimDailyInsight(days, archived, now, now);
  expect(next.earned).toMatchObject({ cardId: 1, sequence: 101 });
  expect(claimDailyInsight(days, next.claims, now + 86400000, now).earned).toBeNull();
});

test('a capped action can earn today once and its claim survives persistence without a replayed notice', () => {
  useStore.setState(useStore.getInitialState(), true);
  useStore.setState({ progressionVersion: 2, xpDay: new Date().toDateString(), dayXpGained: 250 });
  useStore.getState().award(10);
  const state = useStore.getState();
  expect(state.xpTotal).toBe(0);
  expect(state.dailyInsightClaims).toHaveLength(1);
  expect(buildForegroundNotifPayload().dailyInsight).toMatchObject({ day: fmtDate(new Date()), cardId: 1 });
  const saved = JSON.parse(JSON.stringify(collectState()));
  useStore.setState(useStore.getInitialState(), true);
  expect(applyState(saved, true)).toBe(true);
  expect(useStore.getState().dailyInsightClaims).toEqual(state.dailyInsightClaims);
  expect(useStore.getState().dailyInsightNotice).toBeNull();
  useStore.getState().recordProgressDay();
  expect(useStore.getState().dailyInsightClaims).toHaveLength(1);
  expect(useStore.getState().dailyInsightNotice).toBeNull();
  useStore.setState(useStore.getInitialState(), true);
});

test('first activity opens one durable card, calendar dates can be revisited, and empty dates earn nothing', async ({ page }) => {
  await page.clock.install({ time: new Date('2026-10-16T12:00:00+03:00') });
  await page.addInitScript(() => {
    if (!localStorage.getItem('flow_v2')) localStorage.setItem('flow_v2', JSON.stringify({
      progressionVersion: 2, progressDays: [], dailyInsightClaims: [], weekTplSeeded: true,
      saveDate: new Date().toDateString(), tasks: [], qnotes: [],
      preferences: { waterSeedRetired: true },
    }));
  });
  await page.goto('/');
  await expect(page.locator('.insight-notice')).toHaveCount(0);
  await page.getByRole('button', { name: 'Нотатки', exact: true }).click();
  await page.getByRole('textbox', { name: 'Нотатка', exact: true }).fill('Мій перший запис дня');
  await page.clock.runFor(700);
  await expect(page.locator('.insight-notice')).toContainText('Активний день 1');
  await page.locator('.insight-notice button').first().click();
  await expect(page.getByRole('dialog')).toContainText(DAILY_INSIGHTS[0].title);
  await page.getByRole('button', { name: 'Гаразд', exact: true }).click();
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Статистика', exact: true }).click();
  await page.locator('[data-day="2026-10-16"]').click();
  await expect(page.getByRole('dialog')).toContainText(DAILY_INSIGHTS[0].text);
  await page.getByRole('button', { name: 'Гаразд', exact: true }).click();
  await page.locator('[data-day="2026-10-15"]').click();
  await expect(page.getByRole('dialog')).toContainText('День без відкритої думки');
  await page.getByRole('button', { name: 'Гаразд', exact: true }).click();
  await page.reload();
  await expect(page.locator('.insight-notice')).toHaveCount(0);
  const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(saved.progressDays).toEqual(['2026-10-16']);
  expect(saved.dailyInsightClaims).toHaveLength(1);
});
