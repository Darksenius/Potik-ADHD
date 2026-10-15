import { useStore } from '../state/store';

/** Retire only the identifiable legacy routine; a task title never proves its origin. */
export function retireWaterSeed(): void {
  const store = useStore.getState();
  if (store.preferences.waterSeedRetired) return;

  const oldRoutine = store.recur.find(r => r.id === 'r1' && r.nm === 'Вода' && r.unit === 'ml');
  if (oldRoutine) store.deleteRecur(oldRoutine.id);

  useStore.setState(s => ({ preferences: { ...s.preferences, waterSeedRetired: true } }));
}
