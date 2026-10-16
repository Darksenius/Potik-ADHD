#!/usr/bin/env node
/**
 * After Vite builds www/, embed the license and a browsable TEXT source view.
 * The full tracked source (including fonts, images and Gradle wrapper) is a
 * separate release ZIP made by package-source.mjs. Binary files are not text.
 * Also generate the native task catalog from its single source in src/content.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { getSourceRevision } from './source-revision.mjs';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const GITHUB_URL = 'https://github.com/Darksenius/Potik-ADHD';
const SOURCE_FILES = [
  'index.html', 'vite.config.ts', 'tsconfig.json', 'capacitor.config.json',
  'package.json', 'package-lock.json', 'LICENSE', 'README.md',
  'android/build.gradle', 'android/settings.gradle', 'android/variables.gradle',
  'android/gradle.properties', 'android/capacitor.settings.gradle',
  'android/gradlew', 'android/gradlew.bat', 'android/gradle/wrapper/gradle-wrapper.properties',
  'android/app/build.gradle', 'android/app/capacitor.build.gradle',
  'android/app/proguard-rules.pro', 'android/app/src/main/AndroidManifest.xml',
];
const SOURCE_DIRS = [
  { dir: 'src', exts: ['.ts', '.tsx', '.css', '.json', '.txt'] },
  { dir: 'scripts', exts: ['.js', '.mjs', '.mts'] },
  { dir: 'android/app/src/main/java', exts: ['.java', '.kt'] },
  { dir: 'android/app/src/main/res', exts: ['.xml'] },
];
const TARGET = path.join(ROOT, 'www', 'index.html');
const PLACEHOLDER = '<script id="oss-data">window.__OSS__=null;</script>';
const OSS_TAG_RE = /<script id="oss-data">[\s\S]*?<\/script>/;

function walk(dir, exts, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const entryPath = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(entryPath, exts, out);
    else if (exts.includes(path.extname(entry.name))) out.push(entryPath);
  }
}

function collectFiles() {
  const files = SOURCE_FILES.map(file => path.join(ROOT, file));
  for (const { dir, exts } of SOURCE_DIRS) walk(path.join(ROOT, dir), exts, files);
  return [...new Set(files)].sort();
}

if (!fs.existsSync(TARGET)) throw new Error('Run npm run build before embed-oss: www/index.html is missing.');
const html = fs.readFileSync(TARGET, 'utf8');
if (!OSS_TAG_RE.test(html)) throw new Error('The oss-data placeholder is missing from the generated index.html.');
const pristine = html.replace(OSS_TAG_RE, PLACEHOLDER);
if (process.argv.includes('--clean')) {
  fs.writeFileSync(TARGET, pristine);
  console.log('Embedded text removed.');
} else {
  const revision = getSourceRevision(ROOT);
  const github = revision === 'unknown' ? GITHUB_URL : GITHUB_URL + '/tree/' + revision;
  const sourceArchive = revision === 'unknown' ? null : GITHUB_URL + '/archive/' + revision + '.zip';
  const license = fs.readFileSync(path.join(ROOT, 'LICENSE'), 'utf8');
  const files = collectFiles();
  const sections = files.map(file => {
    const name = path.relative(ROOT, file).split(path.sep).join('/');
    const content = fs.readFileSync(file, 'utf8');
    return '## ' + name + '\n\n' + content.split('\n').map(line => '\t' + line).join('\n') + '\n';
  });
  const source = '# Потік — текстові джерела\n\nРевізія: ' + revision + '\n' + github
    + '\n\nЦе текстовий огляд, а не повний архів для збирання. Бінарні шрифти, зображення та Gradle wrapper входять до ZIP вихідного коду.\n'
    + (sourceArchive ? '\nПовний архів цієї ревізії: ' + sourceArchive + '\n' : '\nРевізію не визначено; посилання на конкретний коміт недоступне.\n')
    + '\n' + sections.join('\n');
  const payload = JSON.stringify({ github, revision, sourceArchive, license, source })
    .replace(/</g, '\\u003c').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
  fs.writeFileSync(TARGET, pristine.replace(PLACEHOLDER, '<script id="oss-data">window.__OSS__=' + payload + ';</script>'));

  const catalog = path.join(ROOT, 'src', 'content', 'task-types.json');
  JSON.parse(fs.readFileSync(catalog, 'utf8')); // Fail the build on an invalid source catalog.
  const contracts = path.join(ROOT, 'www', 'contracts');
  fs.mkdirSync(contracts, { recursive: true });
  fs.copyFileSync(catalog, path.join(contracts, 'task-types.json'));
  console.log('Embedded license and text from ' + files.length + ' files; revision ' + revision + '. Generated native task catalog.');
}
