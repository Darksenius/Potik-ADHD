import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { buildQuestTimeline, buildQuestWallDays, getCurrentQuestSegment } from '../src/utils/questPolicy';

test.beforeEach(() => useStore.setState(useStore.getInitialState(), true));

test('a new zone does not inherit a deleted zones day-off exceptions', () => {
  const state = useStore.getState();
  useStore.setState({ zones: state.zones.filter(zone => zone.id !== 2), planDayOff: { '2026-10-15': [2] } });
  const id = useStore.getState().addZone('Інший ранок');
  expect(id).not.toBe(2);
  expect(useStore.getState().planDayOff['2026-10-15']).toEqual([2]);
});

test('overlapping repeated day blocks resolve the same primary zone everywhere', () => {
  const now = new Date(2026, 9, 14, 10, 30);
  useStore.setState({ zones: [
    { id: 11, nm: 'Дім', color: '#abc', slots: [], prio: 1 },
    { id: 12, nm: 'Робота', color: '#def', slots: [], prio: 1 },
  ], planDayZones: { '2026-10-14': [
    { zoneId: 11, s: '09:00', e: '12:00' },
    { zoneId: 12, s: '09:00', e: '12:00' },
    { zoneId: 11, s: '10:00', e: '11:00' },
  ] } });
  const state = useStore.getState();
  const input = { zones: state.zones, dayBlocks: state.dayBlocks, now, days: 1 };
  expect(getCurrentQuestSegment(buildQuestTimeline(input), now)?.zoneId).toBe(11);
  expect(state.getActiveZones({ h: 10, m: 30 }, now)[0].id).toBe(11);
  expect(buildQuestWallDays(input)[0].segments.find(s => s.startMinute <= 630 && s.endMinute > 630)?.zoneId).toBe(11);
});

test('inverted or empty day ranges cannot silently replace a working zone', () => {
  const state = useStore.getState();
  const original = state.zones[0];
  state.updateZone(original.id, { nm: original.nm, color: original.color, desc: '', prio: 1, slots: [{ s: '22:00', e: '06:00' }] });
  expect(useStore.getState().zones[0].slots).toEqual(original.slots);
  state.addDayZone('2026-10-14', original.id, '18:00', '18:00');
  expect(useStore.getState().planDayZones['2026-10-14']).toBeUndefined();
  state.addDayZone('2026-10-14', original.id, '22:00', '24:00');
  state.updateDayZone('2026-10-14', 0, '22:00', '06:00');
  expect(useStore.getState().planDayZones['2026-10-14'][0]).toMatchObject({ s: '22:00', e: '24:00' });
});

test('zone editor explains a midnight crossing and preserves valid end-of-day slots', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('flow_v2', JSON.stringify({ saveDate: new Date().toDateString(), weekTplSeeded: true })));
  await page.goto('/');
  await page.getByRole('button', { name: 'Ще', exact: true }).click();
  await page.getByRole('button', { name: 'Зони', exact: true }).click();
  await page.getByRole('button', { name: 'Редагувати зону Сон' }).click();
  await page.getByLabel('Початок 1', { exact: true }).fill('22:00');
  await page.getByLabel('Кінець 1', { exact: true }).fill('06:00');
  await page.getByRole('button', { name: 'Зберегти зону' }).click();
  await expect(page.getByRole('alert')).toContainText('опівніч');
  await expect(page.getByLabel('Початок 1', { exact: true })).toBeVisible();
  await page.getByLabel('Кінець 1', { exact: true }).fill('00:00');
  await page.getByRole('button', { name: 'Зберегти зону' }).click();
  await expect(page.getByLabel('Початок 1', { exact: true })).toHaveCount(0);
  await page.waitForTimeout(500);
  const state = await page.evaluate(() => JSON.parse(localStorage.getItem('flow_v2')!));
  expect(state.zones[0].slots[0]).toEqual({ s: '22:00', e: '24:00' });
});
