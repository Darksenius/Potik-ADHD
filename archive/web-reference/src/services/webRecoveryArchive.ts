export interface WebRecoverySource { source: string; raw: string; [key: string]: unknown; }
export interface WebRecoveryEntry { savedAt: string; sources: WebRecoverySource[]; [key: string]: unknown; }
export interface RecoveryArchive { name: string; createdAt: number; size: number; }

const LIMIT = 3;
const OPAQUE_SOURCE = 'Нерозпізнаний попередній архів';

function contentKey(entry: WebRecoveryEntry): string {
  return JSON.stringify(entry.sources.map(source => source.raw).sort());
}

function opaque(raw: string, savedAt: string): WebRecoveryEntry {
  return { savedAt, sources: [{ source: OPAQUE_SOURCE, raw }] };
}

function flatten(raw: string, fallbackDate: string, seen = new Set<string>()): WebRecoveryEntry[] {
  if (seen.has(raw)) return [];
  seen.add(raw);
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return [opaque(raw, fallbackDate)];
    const value = parsed as Record<string, unknown>;
    if (value.version === 2 && Array.isArray(value.entries)) {
      return unique(value.entries.flatMap(item => flatten(JSON.stringify(item), fallbackDate, seen)));
    }
    if (typeof value.savedAt !== 'string' || !Array.isArray(value.sources)
      || !value.sources.every(item => !!item && typeof item === 'object' && !Array.isArray(item)
        && typeof (item as Record<string, unknown>).source === 'string'
        && typeof (item as Record<string, unknown>).raw === 'string')) return [opaque(raw, fallbackDate)];
    const { previous, ...current } = value;
    const entries: WebRecoveryEntry[] = [{ ...current, sources: value.sources.map(item => ({ ...(item as WebRecoverySource) })) } as WebRecoveryEntry];
    if (typeof previous === 'string') entries.push(...flatten(previous, value.savedAt, seen));
    else if (previous !== undefined && previous !== null) entries.push(...flatten(JSON.stringify(previous), value.savedAt, seen));
    return entries;
  } catch {
    return [opaque(raw, fallbackDate)];
  }
}

function unique(entries: WebRecoveryEntry[]): WebRecoveryEntry[] {
  const found = new Set<string>();
  return entries.filter(entry => {
    const key = contentKey(entry);
    if (found.has(key)) return false;
    found.add(key);
    return true;
  }).slice(0, LIMIT);
}

function serialize(entries: WebRecoveryEntry[]): string {
  return JSON.stringify({ version: 2, entries: entries.slice(0, LIMIT) });
}

/** Append a unique content snapshot, flattening legacy recursive `previous` strings. */
export function appendWebRecoveryArchive(previousRaw: string | null, next: WebRecoveryEntry): string {
  const older = previousRaw ? flatten(previousRaw, next.savedAt) : [];
  const entries = unique(older);
  if (!entries.some(entry => contentKey(entry) === contentKey(next))) entries.unshift({ ...next, sources: next.sources.map(source => ({ ...source })) });
  return serialize(entries.slice(0, LIMIT));
}

function archiveName(index: number): string {
  return index === 0 ? 'flow-recovery-archive.json' : `flow-recovery-archive-${index + 1}.json`;
}

function entryJson(entry: WebRecoveryEntry): string { return JSON.stringify(entry); }

/** Return newest-first metadata in the persistence layer's established shape. */
export function listWebRecoveryArchives(raw: string | null): RecoveryArchive[] {
  if (!raw) return [];
  return unique(flatten(raw, '')).map((entry, index) => ({
    name: archiveName(index),
    createdAt: Date.parse(entry.savedAt) || 0,
    size: new TextEncoder().encode(entryJson(entry)).length,
  }));
}

/** Read one numbered entry as an independent legacy `{ savedAt, sources }` envelope. */
export function readWebRecoveryArchive(raw: string | null, name?: string): string {
  if (!raw) return '';
  const entries = unique(flatten(raw, ''));
  if (!entries.length) return raw;
  const index = name ? entries.findIndex((_, itemIndex) => archiveName(itemIndex) === name) : 0;
  return index < 0 ? '' : entryJson(entries[index]);
}
