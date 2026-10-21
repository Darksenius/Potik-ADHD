import { useStore } from '../state/store';
import { collectState, saveState } from './persistence';
import { markSavePending } from './saveStatus';
import { flushNativeBackup } from '../bridge/nativeBridge';

let registeredFlush: (() => boolean) | undefined;

/** Flush current persisted state through the app-wide autosave path. */
export function flushAutosave(): boolean {
  return registeredFlush ? registeredFlush() : saveState();
}

/** Persist domain changes, not navigation/toast state. Bounded delay also saves
 * during frequent state changes. Flush before backgrounding. */
export function startAutosave(): () => void {
  const keys = Object.keys(collectState()).filter(k => k !== 'saveDate');
  let pending: ReturnType<typeof setTimeout> | undefined;
  const flush = (): boolean => {
    if (pending !== undefined) clearTimeout(pending);
    pending = undefined;
    // saveState refuses writes while recovery is active and reports that state.
    return saveState();
  };
  registeredFlush = flush;
  const unsubscribe = useStore.subscribe((next, prev) => {
    const changed = keys.some(k => next[k as keyof typeof next] !== prev[k as keyof typeof prev]);
    if (changed) {
      markSavePending();
      if (pending === undefined) pending = setTimeout(flush, 500);
    }
  });
  const flushOnBackground = () => {
    if (flush()) void flushNativeBackup().catch(() => { /* Native status retains the backup error. */ });
  };
  const onVisibility = () => { if (document.visibilityState === 'hidden') flushOnBackground(); };
  window.addEventListener('pagehide', flushOnBackground);
  document.addEventListener('visibilitychange', onVisibility);
  return () => {
    flush();
    unsubscribe();
    if (registeredFlush === flush) registeredFlush = undefined;
    window.removeEventListener('pagehide', flushOnBackground);
    document.removeEventListener('visibilitychange', onVisibility);
  };
}
