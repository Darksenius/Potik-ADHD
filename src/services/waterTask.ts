import { useStore } from '../state/store';
import { hasTaskCounter } from '../utils/taskCounter';

/** Retire the former default water entry once; user-created counters remain ordinary tasks. */
export function retireWaterSeed(): void {
  const store = useStore.getState();
  if (store.preferences.waterSeedRetired) return;

  const oldSeed = store.tasks.find(t => !t.trashed && t.title === 'Вода' && t.type === 'simple'
    && hasTaskCounter(t) && t.counterUnit === 'ml' && t.repeat === 'daily');
  if (oldSeed) store.deleteTask(oldSeed.id);

  const oldRoutine = store.recur.find(r => r.id === 'r1' && r.nm === 'Вода' && r.unit === 'ml');
  if (oldRoutine) store.deleteRecur(oldRoutine.id);

  useStore.setState(s => ({ preferences: { ...s.preferences, waterSeedRetired: true } }));
}
