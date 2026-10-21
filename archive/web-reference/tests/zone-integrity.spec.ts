import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';

test.beforeEach(() => {
  useStore.setState(useStore.getInitialState(), true);
});

test('a deleted zone ID retained by tasks and planner records is not reused', () => {
  const state = useStore.getState();
  useStore.setState({
    zones: [...state.zones, {
      id: 6, nm: 'Майстерня', color: '#123456', slots: [], desc: '', prio: 1,
      active: true,
    }],
    lastZoneId: 6,
    tasks: [{
      id: 700, title: 'Робота в майстерні', type: 'simple', done: false, someday: false,
      created: '2026-10-13T10:00:00.000Z', zoneId: 6,
      questDeferral: {
        kind: 'next-zone', requestedAt: 1, sourceZoneId: 6, targetZoneId: 6,
        startAt: 2, endAt: 3,
      },
    } as any],
    planDayZones: { '2026-10-14': [{ zoneId: 6, s: '10:00', e: '11:00' }] },
    dayTemplates: [{ id: 'workshop', name: 'Майстерня', blocks: [{ zoneId: 6, s: '10:00', e: '11:00' }] }],
  });

  useStore.getState().deleteZone(6);
  const newId = useStore.getState().addZone('Нова зона');

  expect(newId).not.toBe(6);
  expect(useStore.getState().tasks[0]).toMatchObject({ zoneId: 6, questDeferral: { sourceZoneId: 6, targetZoneId: 6 } });
  expect(useStore.getState().planDayZones['2026-10-14'][0].zoneId).toBe(6);
  expect(useStore.getState().dayTemplates[0].blocks[0].zoneId).toBe(6);
});

test('an off zone cannot become active from an explicit day block', () => {
  const state = useStore.getState();
  const date = new Date(2026, 9, 13, 10, 0);
  const day = '2026-10-13';
  useStore.setState({
    zones: state.zones.map(zone => zone.id === 3 ? { ...zone, off: true, active: false } : zone),
    planDayZones: { [day]: [{ zoneId: 3, s: '09:00', e: '11:00' }] },
  });

  const active = useStore.getState().getActiveZones({ h: 10, m: 0 }, date);

  expect(active.some(zone => zone.id === 3)).toBe(false);
  expect(active).toMatchObject([{ id: 0, nm: 'Вільний час' }]);
});
