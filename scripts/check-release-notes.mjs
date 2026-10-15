import { readFileSync } from 'node:fs';

const root = new URL('../', import.meta.url);
const version = JSON.parse(readFileSync(new URL('package.json', root), 'utf8')).version.replace('-r', '.r');
const notes = JSON.parse(readFileSync(new URL('src/content/release-notes.json', root), 'utf8'));
const text = value => typeof value === 'string' && value.trim().length > 0;
const texts = value => Array.isArray(value) && value.length > 0 && value.every(text);
if (!Array.isArray(notes) || notes[0]?.version !== version) {
  throw new Error(`Додай особистий запис «Що нового» для ${version} першим у src/content/release-notes.json.`);
}
const versions = new Set();
for (const note of notes) {
  if (!text(note.version) || versions.has(note.version) || !/^\d{4}-\d{2}-\d{2}$/.test(note.date)
      || !text(note.title) || !text(note.message) || !texts(note.changes) || !text(note.verified)
      || !Array.isArray(note.unfinished) || !note.unfinished.every(text)
      || !Array.isArray(note.checks) || !note.checks.length
      || !note.checks.every(check => text(check.title) && texts(check.steps) && text(check.expected))) {
    throw new Error(`Запис «Що нового» ${note.version} має містити пояснення, перевірки й очікуваний результат.`);
  }
  versions.add(note.version);
}
console.log(`Що нового: запис ${version} готовий, попередніх версій — ${notes.length - 1}.`);
