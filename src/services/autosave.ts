import { useStore } from '../state/store';
import { collectState, saveState } from './persistence';

/** Persist domain changes, not navigation/toast state. Bounded delay also saves
 * while a Pomodoro timer is continuously updating. Flush before backgrounding. */
export function startAutosave(): () => void {
  const keys = Object.keys(collectState()).filter(k => k !== 'saveDate');
  let pending: ReturnType<typeof setTimeout> | undefined;
  const flush = () => {
    if (pending !== undefined) clearTimeout(pending);
    pending = undefined;
    // Never overwrite a recovery file while its owner is deciding to restore it.
    if (!useStore.getState().backupBanner) saveState();
  };
  const unsubscribe = useStore.subscribe((next, prev) => {
    const changed = keys.some(k => next[k as keyof typeof next] !== prev[k as keyof typeof prev]);
    if (changed && pending === undefined) pending = setTimeout(flush, 500);
  });
  const onVisibility = () => { if (document.visibilityState === 'hidden') flush(); };
  window.addEventListener('pagehide', flush);
  document.addEventListener('visibilitychange', onVisibility);
  return () => {
    flush();
    unsubscribe();
    window.removeEventListener('pagehide', flush);
    document.removeEventListener('visibilitychange', onVisibility);
  };
}
