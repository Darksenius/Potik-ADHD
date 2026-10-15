export interface SaveStatus {
  phase: 'saved' | 'pending' | 'error';
  message?: string;
  savedAt?: number;
}

type SaveStatusListener = () => void;

let snapshot: SaveStatus = Object.freeze({ phase: 'saved' });
const listeners = new Set<SaveStatusListener>();

function publish(next: SaveStatus): void {
  if (snapshot.phase === next.phase && snapshot.message === next.message && snapshot.savedAt === next.savedAt) return;
  snapshot = Object.freeze(next);
  listeners.forEach(listener => {
    try { listener(); } catch { /* A status subscriber cannot change save success. */ }
  });
}

export function getSaveStatus(): SaveStatus {
  return snapshot;
}

export function subscribeSaveStatus(listener: SaveStatusListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function markSavePending(): void {
  if (snapshot.phase === 'pending' && !snapshot.message) return;
  publish({ phase: 'pending', savedAt: snapshot.savedAt });
}

export function markSaveSaved(): void {
  publish({ phase: 'saved', savedAt: Date.now() });
}

export function markSaveError(message: string): void {
  publish({ phase: 'error', message, savedAt: snapshot.savedAt });
}
