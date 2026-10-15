import { test, expect } from '@playwright/test';
import { useStore } from '../src/state/store';
import { mergeState } from '../src/services/exportImport';

const zone = (id: number, nm: string) => ({ id, nm, color: '#123456', slots: [], desc: '', prio: 1, active: true });
const deferral = (sourceZoneId: number, targetZoneId: number) => ({
  kind: 'next-zone' as const,
  requestedAt: 100,
  sourceZoneId,
  targetZoneId,
  startAt: 200,
  endAt: 300,
});

test.beforeEach(() => useStore.setState(useStore.getInitialState(), true));

test('merge allocates around orphaned zone references and remaps deferrals consistently on reimport', () => {
  const orphan = 6;
  useStore.setState({
    zones: [1, 2, 3, 4, 5].map(id => zone(id, `Зона ${id}`)),
    tasks: [{ id: 1, title: 'Стара задача', type: 'simple', done: false, someday: false, zoneId: orphan } as any],
    dayTemplates: [{ id: 'old-template', name: 'Старий шаблон', blocks: [{ zoneId: orphan, s: '10:00', e: '11:00' }] }],
    lastZoneId: orphan,
    nid: 2,
  });

  const backup = {
    zones: [zone(orphan, 'Імпортована зона')],
    tasks: [
      { id: 10, title: 'Відкладена справа', zoneId: orphan, questDeferral: deferral(orphan, orphan) },
      { id: 11, title: 'Завершена відкладена справа', zoneId: orphan, done: true, completionDeferral: deferral(orphan, orphan) },
    ],
  };

  expect(mergeState(backup)).toBe(3);
  const first = useStore.getState();
  const importedZone = first.zones.find(item => item.nm === 'Імпортована зона');
  expect(importedZone?.id).toBe(7);
  expect(first.tasks.find(item => item.title === 'Стара задача')?.zoneId).toBe(orphan);
  expect(first.dayTemplates[0].blocks[0].zoneId).toBe(orphan);
  expect(first.tasks.find(item => item.title === 'Відкладена справа')).toMatchObject({
    zoneId: importedZone?.id,
    questDeferral: { sourceZoneId: importedZone?.id, targetZoneId: importedZone?.id },
  });
  expect(first.tasks.find(item => item.title === 'Завершена відкладена справа')).toMatchObject({
    zoneId: importedZone?.id,
    completionDeferral: { sourceZoneId: importedZone?.id, targetZoneId: importedZone?.id },
  });

  expect(mergeState(backup)).toBe(0);
  expect(useStore.getState().tasks.filter(item => item.title.toLocaleLowerCase('uk').includes('відкладена справа'))).toHaveLength(2);
});

test('imported deferral IDs map to the imported zone when the same source ID belongs to another current zone', () => {
  useStore.setState({ zones: [zone(8, 'Поточна зона')], tasks: [], lastZoneId: 0, nid: 1 });
  const backup = {
    zones: [zone(8, 'Імпортована зона')],
    tasks: [{ id: 10, title: 'Справа з відкладенням', zoneId: 8, questDeferral: deferral(8, 8) }],
  };

  expect(mergeState(backup)).toBe(2);
  const state = useStore.getState();
  const importedZone = state.zones.find(item => item.nm === 'Імпортована зона');
  expect(importedZone?.id).toBe(1);
  expect(state.zones.find(item => item.nm === 'Поточна зона')?.id).toBe(8);
  expect(state.tasks[0]).toMatchObject({
    zoneId: importedZone?.id,
    questDeferral: { sourceZoneId: importedZone?.id, targetZoneId: importedZone?.id },
  });

  expect(mergeState(backup)).toBe(0);
  expect(useStore.getState().tasks).toHaveLength(1);
});
