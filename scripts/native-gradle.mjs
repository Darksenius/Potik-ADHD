import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { readFileSync } from 'node:fs';

const tasks = {
  build: [':app:assembleDebug'],
  test: [':app:testDebugUnitTest', ':app:assembleDebugAndroidTest'],
  device: ['-PflowTestBuildType=nativeDebug', ':app:connectedNativeDebugAndroidTest'],
};
const args = tasks[process.argv[2]];
if (!args) throw new Error('Use build, test or device.');
const root = fileURLToPath(new URL('../', import.meta.url));
const notes = JSON.parse(readFileSync(new URL('../src/content/release-notes.json', import.meta.url), 'utf8'));
const version = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version.replace('-r', '.r');
if (notes[0]?.version !== version) throw new Error('Release notes must match the package version.');
const result = spawnSync(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [...args, '--console=plain'], {
  cwd: `${root}/android`, stdio: 'inherit', shell: process.platform === 'win32',
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
