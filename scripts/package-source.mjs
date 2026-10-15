import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';
import { getSourceRevision } from './source-revision.mjs';

export function packageSource(root = fileURLToPath(new URL('../', import.meta.url))) {
  const revision = getSourceRevision(root);
  if (revision === 'unknown') throw new Error('Packaging a release requires an exact source revision.');
  const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  if (revision !== head) throw new Error('SOURCE_REVISION must match the checked-out release commit.');
  const version = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')).version.replace('-r', '.r');
  const committedVersion = JSON.parse(execFileSync('git', ['show', `${revision}:package.json`], { cwd: root, encoding: 'utf8' })).version.replace('-r', '.r');
  if (version !== committedVersion) throw new Error('Commit package.json before packaging the release source.');
  if (execFileSync('git', ['status', '--porcelain', '--untracked-files=normal'], { cwd: root, encoding: 'utf8' }).trim()) {
    throw new Error('Package release source from a clean checkout so the archive matches the built files.');
  }
  const output = path.join(root, 'dist', `Potik-v${version}-source.zip`);
  mkdirSync(path.dirname(output), { recursive: true });
  const prefix = `Potik-v${version}/`;
  execFileSync('git', ['archive', '--format=zip', `--prefix=${prefix}`, `--add-virtual-file=${prefix}SOURCE_REVISION:${revision}\n`, '-o', output, revision], { cwd: root });
  console.log(`Complete tracked source archive: ${output}\nRevision: ${revision}`);
  return { output, revision, version };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) packageSource();
