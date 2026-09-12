#!/usr/bin/env node
/*
 * embed-oss.js — вшиває текст ліцензії (LICENSE) та ПОВНИЙ вихідний код
 * у www/index.html (Довідка → «Ліцензія та вихідний код»), як вимагає AGPL v3.
 *
 * ⚠ ЗМІНА ПРОТИ ПОПЕРЕДНЬОЇ ВЕРСІЇ: www/index.html тепер ЗГЕНЕРОВАНИЙ
 *   `vite build` файл (не комітиться в git), тому цей скрипт МУСИТЬ
 *   запускатись ПІСЛЯ збірки, не до неї:
 *
 *     npm run build              — vite build → www/
 *     node scripts/embed-oss.js  — вшити ліцензію+код у ЩОЙНО зібраний www/index.html
 *     npx cap sync android
 *
 *   (саме такий порядок вже прописаний у package.json → "sync" та в
 *   .github/workflows/release.yml)
 *
 * Запуск:
 *   node scripts/embed-oss.js          — вшити (після `npm run build`)
 *   node scripts/embed-oss.js --clean  — прибрати вшите, повернути плейсхолдер
 *
 * Скрипт ідемпотентний: повторний запуск дає той самий результат.
 *
 * ⚠ ЯКЩО ДОДАЄШ / ПЕРЕЙМЕНОВУЄШ / ВИДАЛЯЄШ ФАЙЛИ КОДУ —
 *   онови списки SOURCE_FILES та SOURCE_DIRS нижче.
 */
// package.json тепер "type":"module" (сучасний Vite-стиль), тому ESM-синтаксис,
// не CommonJS require() — інакше падає з "require is not defined in ES module scope".
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.join(__dirname, '..');
const GITHUB_URL = 'https://github.com/Darksenius/Potik-ADHD';

// Окремі файли вихідного коду (шляхи відносно кореня репозиторію).
// www/index.html БІЛЬШЕ НЕ ТУТ — це згенерований build-артефакт, не джерело.
// Натомість джерело — кореневий index.html (вхідна точка Vite).
const SOURCE_FILES = [
  'index.html',
  'vite.config.ts',
  'tsconfig.json',
  'capacitor.config.json',
  'package.json',
  'android/app/src/main/AndroidManifest.xml',
  'scripts/embed-oss.js',
];

// Теки, з яких рекурсивно беруться всі файли з указаними розширеннями
const SOURCE_DIRS = [
  { dir: 'src', exts: ['.ts', '.tsx', '.css'] },
  { dir: 'android/app/src/main/java', exts: ['.java'] },
];

const TARGET = path.join(ROOT, 'www', 'index.html');
const PLACEHOLDER = '<script id="oss-data">window.__OSS__=null;</script>';
// Збігається і з плейсхолдером, і з уже вшитими даними (всередині payload
// усі «<» екрановані, тому перший літеральний </script> — справжній)
const OSS_TAG_RE = /<script id="oss-data">[\s\S]*?<\/script>/;

function walk(dir, exts, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, exts, out);
    else if (exts.includes(path.extname(e.name))) out.push(p);
  }
}

function collectFiles() {
  const abs = SOURCE_FILES.map(f => path.join(ROOT, f));
  for (const { dir, exts } of SOURCE_DIRS) {
    const found = [];
    walk(path.join(ROOT, dir), exts, found);
    found.sort();
    abs.push(...found);
  }
  // без дублів
  return [...new Set(abs)];
}

function relUnix(p) {
  return path.relative(ROOT, p).split(path.sep).join('/');
}

function main() {
  const clean = process.argv.includes('--clean');

  if (!fs.existsSync(TARGET)) {
    console.error(
      'ПОМИЛКА: www/index.html не знайдено. Він тепер генерується збіркою — ' +
      'спершу запусти `npm run build` (vite build), а вже потім embed-oss.js.'
    );
    process.exit(1);
  }

  const html = fs.readFileSync(TARGET, 'utf8');
  if (!OSS_TAG_RE.test(html)) {
    console.error(
      'ПОМИЛКА: у www/index.html не знайдено <script id="oss-data"> — ' +
      'плейсхолдер видалили з кореневого index.html (вхідної точки Vite)?'
    );
    process.exit(1);
  }
  // нормалізуємо до чистого стану (як одразу після `vite build`, без вшитих даних)
  const pristine = html.replace(OSS_TAG_RE, PLACEHOLDER);

  if (clean) {
    fs.writeFileSync(TARGET, pristine);
    console.log('Вшиті дані прибрано, плейсхолдер відновлено.');
    return;
  }

  const license = fs.readFileSync(path.join(ROOT, 'LICENSE'), 'utf8');

  // Markdown-документ: заголовок ## на кожен файл, вміст — код з відступом табуляцією
  const files = collectFiles();
  const sections = [];
  for (const absPath of files) {
    const rel = relUnix(absPath);
    const content = fs.readFileSync(absPath, 'utf8');
    sections.push('## ' + rel + '\n\n' + content.split('\n').map(l => '\t' + l).join('\n') + '\n');
  }
  const source = '# Потік — повний вихідний код\n\n' + GITHUB_URL + '\n\n' + sections.join('\n');

  // «<» → \u003c, щоб жоден </script> чи <!-- усередині даних не зламав HTML;
  // U+2028/U+2029 → escape, бо вони ламають JS-рядки у старих парсерах.
  // Після парсингу рушієм текст повертається 1:1.
  const payload = JSON.stringify({ github: GITHUB_URL, license: license, source: source })
    .replace(/</g, '\\u003c')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');

  const injected = pristine.replace(
    PLACEHOLDER,
    '<script id="oss-data">window.__OSS__=' + payload + ';</script>'
  );
  fs.writeFileSync(TARGET, injected);
  const kb = n => (n / 1024).toFixed(1) + ' КБ';
  console.log('Вшито: ліцензія ' + kb(license.length) + ', вихідний код ' + kb(source.length) +
    ' (' + files.length + ' файлів). www/index.html тепер: ' + kb(injected.length));
}

main();
