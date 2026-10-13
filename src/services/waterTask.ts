import { useStore } from '../state/store';
import { fmtDate } from '../utils/date';
import { hasTaskCounter } from '../utils/taskCounter';

/** Water uses the same task model, UI and notification actions as any counter. */
export function initializeWaterTask(firstUse: boolean): void {
  const store = useStore.getState();
  const old = store.recur.find(r => r.id === 'r1' && r.nm === 'Вода' && r.unit === 'ml');
  if (!firstUse && !old) return;
  if (!store.tasks.some(t => !t.trashed && t.title === 'Вода' && hasTaskCounter(t))) {
    const id = store.saveTask(null, {
      title: 'Вода', type: 'simple', repeat: 'daily', counterEnabled: true,
      counterStep: old?.step || 100, counterUnit: 'ml', counterTarget: 0,
    });
    useStore.setState(s => ({ tasks: s.tasks.map(t => t.id === id ? { ...t, counter: old?.val || 0, counterCreditedProgress: old?.val || 0, counterDay: fmtDate(new Date()), expanded: true } : t) }));
  }
  if (old) store.deleteRecur(old.id);
}
