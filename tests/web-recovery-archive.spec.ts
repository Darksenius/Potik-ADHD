import { test, expect } from '@playwright/test';
import { appendWebRecoveryArchive, listWebRecoveryArchives, readWebRecoveryArchive } from '../src/services/webRecoveryArchive';

const entry = (savedAt: string, raw: string, source = 'Android') => ({ savedAt, sources: [{ source, raw }] });

test('ten retries of identical content remain one stable archive regardless of date and source label', () => {
  const raw = '{ "дані": "збережено\nточно" }';
  let archive: string | null = null;
  for (let retry = 0; retry < 10; retry += 1) {
    archive = appendWebRecoveryArchive(archive, entry(`2026-10-${String(retry + 1).padStart(2, '0')}T10:00:00.000Z`, raw, `source-${retry}`));
  }

  expect(listWebRecoveryArchives(archive)).toHaveLength(1);
  expect(JSON.parse(readWebRecoveryArchive(archive))).toEqual(entry('2026-10-01T10:00:00.000Z', raw, 'source-0'));
  expect(JSON.parse(readWebRecoveryArchive(archive)).sources[0].raw).toBe(raw);
});

test('five distinct payloads retain only the newest three with stable numbered filenames', () => {
  let archive: string | null = null;
  for (let index = 1; index <= 5; index += 1) {
    archive = appendWebRecoveryArchive(archive, entry(`2026-10-0${index}T10:00:00.000Z`, `payload-${index}`));
  }

  const stored = JSON.parse(archive || '');
  expect(stored.version).toBe(2);
  expect(stored.entries).toHaveLength(3);
  expect(stored.entries.every((item: unknown) => !('previous' in (item as Record<string, unknown>)))).toBe(true);
  expect(listWebRecoveryArchives(archive).map(item => item.name)).toEqual([
    'flow-recovery-archive.json', 'flow-recovery-archive-2.json', 'flow-recovery-archive-3.json',
  ]);
  expect([1, 2, 3].map(index => JSON.parse(readWebRecoveryArchive(archive, `flow-recovery-archive${index === 1 ? '' : `-${index}`}.json`)).sources[0].raw))
    .toEqual(['payload-5', 'payload-4', 'payload-3']);
});

test('legacy recursive previous envelopes flatten newest first and preserve source payloads', () => {
  const oldest = entry('2026-10-01T10:00:00.000Z', 'old-bytes');
  const middle = { ...entry('2026-10-02T10:00:00.000Z', 'middle-bytes'), previous: JSON.stringify(oldest) };
  const archive = appendWebRecoveryArchive(JSON.stringify(middle), entry('2026-10-03T10:00:00.000Z', 'new-bytes'));

  expect(listWebRecoveryArchives(archive).map(item => item.createdAt)).toEqual([
    Date.parse('2026-10-03T10:00:00.000Z'), Date.parse('2026-10-02T10:00:00.000Z'), Date.parse('2026-10-01T10:00:00.000Z'),
  ]);
  const third = JSON.parse(readWebRecoveryArchive(archive, 'flow-recovery-archive-3.json'));
  expect(third.sources[0].raw).toBe('old-bytes');
  expect(third).not.toHaveProperty('previous');
});

test('corrupt previous data is retained as an opaque source when appending a valid entry', () => {
  const malformed = '{broken archive bytes';
  const archive = appendWebRecoveryArchive(malformed, entry('2026-10-05T10:00:00.000Z', 'new-bytes'));

  expect(listWebRecoveryArchives(archive)).toHaveLength(2);
  const opaque = JSON.parse(readWebRecoveryArchive(archive, 'flow-recovery-archive-2.json'));
  expect(opaque.sources[0].raw).toBe(malformed);
});

test('metadata and named exports are independent copies of the stored archive entries', () => {
  const archive = appendWebRecoveryArchive(null, {
    savedAt: '2026-10-06T10:00:00.000Z',
    sources: [{ source: 'Android', raw: 'payload', extra: { preserved: true } }],
  });
  const listed = listWebRecoveryArchives(archive);
  listed[0].name = 'changed.json';

  expect(listWebRecoveryArchives(archive)[0].name).toBe('flow-recovery-archive.json');
  expect(JSON.parse(readWebRecoveryArchive(archive)).sources[0]).toEqual({
    source: 'Android', raw: 'payload', extra: { preserved: true },
  });
  expect(readWebRecoveryArchive(archive, 'missing.json')).toBe('');
});
